# jev-java

**Unofficial** Java 21+ SDK for [TypeSafe AI](https://typesafe.ai)'s Jev (System One API).
This project is not affiliated with, endorsed by, or supported by TypeSafe AI.

Status: pre-release. The wire types, sealed answers, and the HTTP client with retries, deadline,
cancellation and shutdown are complete; helpers, Micrometer metrics, a recording test fake and a
runnable example are next. See [docs/PARITY.md](docs/PARITY.md) for how each behaviour compares
with the official Python and JavaScript SDKs and the live API.

## Building

```sh
./mvnw verify          # compile (-Werror), Checkstyle, tests, JaCoCo >= 85 % on core, SpotBugs
pre-commit install --install-hooks   # once per clone; runs the same gate before every commit
```
