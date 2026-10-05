"""Exercise configured Java gates with tiny disposable compatible and broken artifacts."""
import copy
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def find_jdk():
    """Honor an explicit JAVA_HOME; otherwise resolve the compiler on PATH."""
    configured = os.environ.get('JAVA_HOME')
    compiler = None if configured else shutil.which('javac')
    if not configured and not compiler:
        raise ValueError('No javac on PATH; set JAVA_HOME to a complete JDK 21 or newer')
    home = Path(configured).resolve() if configured else Path(compiler).resolve().parent.parent
    suffix = '.exe' if os.name == 'nt' else ''
    for tool in ('java', 'javac', 'jar'):
        executable = home / 'bin' / (tool + suffix)
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ValueError(f'{home} lacks executable {tool}; set JAVA_HOME to a complete JDK 21 or newer')
    return home


def run(args, cwd, marker=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=180)
    output = result.stdout + result.stderr
    if marker is None:
        if result.returncode:
            raise AssertionError(output)
    elif result.returncode == 0 or marker not in output:
        raise AssertionError(f'Expected failure containing {marker!r}\n{output}')
    return output


def plugin(tree, artifact, profile=None):
    base = tree if profile is None else next(p for p in tree.findall('./profiles/profile')
                                            if p.findtext('id') == profile)
    return copy.deepcopy(next(p for p in base.findall('./build/plugins/plugin')
                              if p.findtext('artifactId') == artifact))


def pom(path, plugins, properties=None):
    project = ET.Element('project')
    for key, value in [('modelVersion', '4.0.0'), ('groupId', 'net.codefinch.jev'),
                       ('artifactId', 'jev-core'), ('version', '0.1.1')]:
        ET.SubElement(project, key).text = value
    props = ET.SubElement(project, 'properties')
    for key, value in (properties or {}).items():
        ET.SubElement(props, key).text = value
    build = ET.SubElement(ET.SubElement(project, 'build'), 'plugins')
    for item in plugins:
        build.append(item)
    ET.ElementTree(project).write(path / 'pom.xml', encoding='unicode')


def main():
    tree = ET.parse(ROOT / 'pom.xml').getroot()
    for node in tree.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    java_home = find_jdk()
    os.environ['JAVA_HOME'] = str(java_home)
    print('Gate proof JDK:', java_home, flush=True)
    suffix = '.exe' if os.name == 'nt' else ''
    javac, jar = (str(java_home / 'bin' / (tool + suffix)) for tool in ('javac', 'jar'))
    mvn = [str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress']
    with tempfile.TemporaryDirectory(prefix='jev-gates-') as temp:
        work = Path(temp)

        def fixture(name, method, internal='public void hidden() {}'):
            folder = work / name
            source = folder / 'src/net/codefinch/jev'
            source.mkdir(parents=True)
            (source / 'Probe.java').write_text('package net.codefinch.jev; public class Probe {' + method + '}')
            hidden = source / 'internal'
            hidden.mkdir()
            (hidden / 'Detail.java').write_text('package net.codefinch.jev.internal; public class Detail {' + internal + '}')
            classes = folder / 'classes'
            run([javac, '--release', '21', '-d', str(classes), *map(str, source.rglob('*.java'))], work)
            archive = folder / 'fixture.jar'
            run([jar, '--create', '--file', str(archive), '-C', str(classes), '.'], work)
            return archive

        old = fixture('old', 'public String value() {return "old";}')
        good = fixture('good', 'public String value() {return "new";} public int added(){return 1;}', '')
        bad = fixture('bad', '')
        generic_old = fixture('generic-old', 'public java.util.List<String> values(){return null;}')
        generic_new = fixture('generic-new', 'public java.util.List<Integer> values(){return null;}')
        api = plugin(tree, 'japicmp-maven-plugin', 'api-compat')
        conf = api.find('configuration')
        conf.remove(conf.find('oldVersion'))
        for key, archive in [('oldVersion', old), ('newVersion', good)]:
            ET.SubElement(ET.SubElement(ET.SubElement(conf, key), 'file'), 'path').text = str(archive)
        api.remove(api.find('executions'))
        pom(work, [api], {'api.compatibility.required': 'true'})
        run(mvn + ['package', 'japicmp:cmp'], work)
        print('PASS: compatible addition and internal removal accepted')
        conf.find('newVersion/file/path').text = str(bad)
        pom(work, [api], {'api.compatibility.required': 'true'})
        run(mvn + ['package', 'japicmp:cmp'], work, 'METHOD_REMOVED')
        print('PASS: public binary break rejected')
        conf.find('oldVersion/file/path').text = str(generic_old)
        conf.find('newVersion/file/path').text = str(generic_new)
        pom(work, [api], {'api.compatibility.required': 'true'})
        run(mvn + ['package', 'japicmp:cmp'], work, 'METHOD_RETURN_TYPE_GENERICS_CHANGED')
        print('PASS: erased-signature-compatible source break rejected')
        conf.find('oldVersion/file/path').text = str(work / 'missing.jar')
        pom(work, [api], {'api.compatibility.required': 'true'})
        run(mvn + ['package', 'japicmp:cmp'], work, 'missing.jar')
        print('PASS: missing baseline fails closed')

        compiler = plugin(tree, 'maven-compiler-plugin', 'analysis')
        version = next(p.findtext('version') for p in tree.findall('./build/pluginManagement/plugins/plugin')
                       if p.findtext('artifactId') == 'maven-compiler-plugin')
        ET.SubElement(compiler, 'version').text = version
        ET.SubElement(compiler.find('configuration'), 'release').text = '21'
        pom(work, [compiler], {'error-prone.version': tree.findtext('./properties/error-prone.version')})
        source = work / 'src/main/java/Defect.java'
        source.parent.mkdir(parents=True)
        source.write_text('class Defect { void broken() { new IllegalStateException("lost"); } }')
        run(mvn + ['clean', 'compile'], work, '[DeadException]')
        print('PASS: Error Prone rejects a discarded exception')
        source.write_text('class Defect { void fixed() { throw new IllegalStateException("thrown"); } }')
        run(mvn + ['clean', 'compile'], work)
        print('PASS: repaired analysis fixture compiles')
        source.write_text('class Defect { boolean broken(int[] a, int[] b) { return a.equals(b); } }')
        run(mvn + ['clean', 'compile'], work, '[ArrayEquals]')
        print('PASS: default Error Prone check rejects array identity equality')
        source.write_text('class Defect { boolean fixed(int[] a, int[] b) { return java.util.Arrays.equals(a, b); } }')
        run(mvn + ['clean', 'compile'], work)
        print('PASS: repaired default-check fixture compiles')

        jacoco = copy.deepcopy(next(p for p in tree.findall('./build/pluginManagement/plugins/plugin')
                                     if p.findtext('artifactId') == 'jacoco-maven-plugin'))
        agent = Path.home() / ('.m2/repository/org/jacoco/org.jacoco.agent/' + jacoco.findtext('version'))
        agent = agent / ('org.jacoco.agent-' + jacoco.findtext('version') + '-runtime.jar')
        probe = work / 'CoverageProbe.java'
        probe.write_text('class CoverageProbe { public static void main(String[] args) {} }')
        run([javac, '-d', str(work), str(probe)], work)
        empty = work / 'uncovered.exec'
        run([str(java_home / 'bin' / ('java' + suffix)), '-javaagent:' + str(agent) + '=destfile=' + str(empty),
             '-cp', str(work), 'CoverageProbe'], work)
        for module in ('jev-core', 'jev-test', 'jev-micrometer'):
            shutil.rmtree(work / 'target', ignore_errors=True)
            shutil.copytree(ROOT / module / 'target/classes', work / 'target/classes')
            module_pom = ET.parse(ROOT / module / 'pom.xml').getroot()
            floor = module_pom.findtext('./{*}properties/{*}jacoco.line.minimum')
            floor = floor or tree.findtext('./properties/jacoco.line.minimum')
            pom(work, [jacoco], {'jacoco.line.minimum': floor})
            shutil.copyfile(empty, work / 'target/jacoco.exec')
            run(mvn + ['jacoco:check@check'], work, 'Coverage checks have not been met')
            shutil.copyfile(ROOT / module / 'target/jacoco.exec', work / 'target/jacoco.exec')
            run(mvn + ['jacoco:check@check'], work)
            print('PASS: ' + module + ' rejects unexecuted classes and accepts measured coverage')

        release = plugin(tree, 'maven-enforcer-plugin', 'release-checks')
        version = next(p.findtext('version') for p in tree.findall('./build/pluginManagement/plugins/plugin')
                       if p.findtext('artifactId') == 'maven-enforcer-plugin')
        ET.SubElement(release, 'version').text = version
        pom(work, [release])
        run(mvn + ['validate'], work)
        source_pom = work / 'pom.xml'
        source_pom.write_text(source_pom.read_text().replace('<version>0.1.1</version>',
                                                           '<version>0.1.1-SNAPSHOT</version>'))
        run(mvn + ['validate'], work, 'RequireReleaseVersion')
        print('PASS: release gate accepts a fixed release and rejects a snapshot')

        buildplan = plugin(tree, 'maven-artifact-plugin')
        old_jar = ET.Element('plugin')
        ET.SubElement(old_jar, 'groupId').text = 'org.apache.maven.plugins'
        ET.SubElement(old_jar, 'artifactId').text = 'maven-jar-plugin'
        ET.SubElement(old_jar, 'version').text = '2.3'
        pom(work, [buildplan, old_jar], {'project.build.outputTimestamp': '2026-09-20T00:00:00Z'})
        run(mvn + ['validate'], work,
            'plugin with non-reproducible output: org.apache.maven.plugins:maven-jar-plugin:2.3')
        print('PASS: reproducibility gate rejects an old non-reproducible JAR plugin')


if __name__ == '__main__':
    main()
