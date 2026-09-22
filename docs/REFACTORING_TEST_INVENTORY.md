# Refactoring test inventory

Baseline: `04b729e` (same implementation as Phase 3 sign-off). Method names, parameter sources and assertions are retained. Tests not listed here remain in their original classes. Shared transport setup moved to `HttpTestFixture`; observer recording stays in `CallObserverTest.Recording`.

| Original test method | New test method |
|---|---|
| `HttpJevClientTest.systemOneSendsTheDocumentedRequestAndParsesTheResponse` | `HttpRequestTest.systemOneSendsTheDocumentedRequestAndParsesTheResponse` |
| `HttpJevClientTest.modelsSendsGetWithoutBodyOrContentType` | `HttpRequestTest.modelsSendsGetWithoutBodyOrContentType` |
| `HttpJevClientTest.asyncVariantsWork` | `HttpRequestTest.asyncVariantsWork` |
| `HttpJevClientTest.sdkHeadersAlwaysWinAndMergeIsCaseInsensitive` | `HttpRequestTest.sdkHeadersAlwaysWinAndMergeIsCaseInsensitive` |
| `HttpJevClientTest.perCallModelOverridesAndConcurrentCallsUseTheirOwnModel` | `HttpRequestTest.perCallModelOverridesAndConcurrentCallsUseTheirOwnModel` |
| `HttpJevClientTest.baseUrlPrefixAndTrailingSlashesAreHandled` | `HttpRequestTest.baseUrlPrefixAndTrailingSlashesAreHandled` |
| `HttpJevClientTest.missingKeyStyle403IsPermissionDeniedWithTheServerMessage` | `HttpRequestTest.missingKeyStyle403IsPermissionDeniedWithTheServerMessage` |
| `HttpJevClientTest.malformedSuccessBodyIsValidationErrorWithEndpoint` | `HttpRequestTest.malformedSuccessBodyIsValidationErrorWithEndpoint` |
| `HttpJevClientTest.unknownAnswerTypeIsDroppedEndToEnd` | `HttpRequestTest.unknownAnswerTypeIsDroppedEndToEnd` |
| `HttpJevClientTest.redirectsAreNeverFollowedAndInjectedTransportsMustAgree` | `HttpRequestTest.redirectsAreNeverFollowedAndInjectedTransportsMustAgree` |
| `HttpJevClientTest.nonRetriedStatusesMapWithoutRetrying` | `HttpRetryTest.nonRetriedStatusesMapWithoutRetrying` |
| `HttpJevClientTest.retriesThenSucceedsWithRetryCountHeaderAndBackoff` | `HttpRetryTest.retriesThenSucceedsWithRetryCountHeaderAndBackoff` |
| `HttpJevClientTest.retriesExhaustedThrowsTheLastFailure` | `HttpRetryTest.retriesExhaustedThrowsTheLastFailure` |
| `HttpJevClientTest.retryAfterIsHonouredPreferringMsAndIgnoredAboveTheCap` | `HttpRetryTest.retryAfterIsHonouredPreferringMsAndIgnoredAboveTheCap` |
| `HttpJevClientTest.perCallRetryOverrideIsPartial` | `HttpRetryTest.perCallRetryOverrideIsPartial` |
| `HttpJevClientTest.connectionFailureIsRetriedThenMapped` | `HttpRetryTest.connectionFailureIsRetriedThenMapped` |
| `HttpJevClientTest.predicateCanOptIntoRetryingValidationFailures` | `HttpRetryTest.predicateCanOptIntoRetryingValidationFailures` |
| `HttpJevClientTest.stalledHeadersTimeOutPerAttemptAndAreRetried` | `HttpDeadlineTest.stalledHeadersTimeOutPerAttemptAndAreRetried` |
| `HttpJevClientTest.stalledBodyTimesOutToo` | `HttpDeadlineTest.stalledBodyTimesOutToo` |
| `HttpJevClientTest.deadlineRefusesRetryThatWouldBreachIt` | `HttpDeadlineTest.deadlineRefusesRetryThatWouldBreachIt` |
| `HttpJevClientTest.deadlineExpiringDuringAnActiveAttemptCancelsIt` | `HttpDeadlineTest.deadlineExpiringDuringAnActiveAttemptCancelsIt` |
| `HttpJevClientTest.deadlineCanBeDisabledPerCallAndPerClient` | `HttpDeadlineTest.deadlineCanBeDisabledPerCallAndPerClient` |
| `HttpJevClientTest.cancelBeforeStartNeverSendsRequest` | `HttpCancellationTest.cancelBeforeStartNeverSendsRequest` |
| `HttpJevClientTest.cancelDuringAnAttemptAbortsIt` | `HttpCancellationTest.cancelDuringAnAttemptAbortsIt` |
| `HttpJevClientTest.cancelDuringBackoffStartsNoFurtherAttemptEvenWithPermissivePredicate` | `HttpCancellationTest.cancelDuringBackoffStartsNoFurtherAttemptEvenWithPermissivePredicate` |
| `HttpJevClientTest.cancelRacingTheNextRetryIsRespectedOnBothExecutors` | `HttpCancellationTest.cancelRacingTheNextRetryIsRespectedOnBothExecutors` |
| `HttpJevClientTest.interruptingSyncCallThrowsAndReassertsTheFlag` | `HttpCancellationTest.interruptingSyncCallThrowsAndReassertsTheFlag` |
| `HttpJevClientTest.explicitCancellationReleasesTrackingInEveryState` | `HttpCancellationTest.explicitCancellationReleasesTrackingInEveryState` |
| `HttpJevClientTest.closeWaitsThenCancelsAndRejectsNewCalls` | `HttpShutdownTest.closeWaitsThenCancelsAndRejectsNewCalls` |
| `HttpJevClientTest.closeWithSdkOwnedResourcesTerminatesThem` | `HttpShutdownTest.closeWithSdkOwnedResourcesTerminatesThem` |
| `HttpJevClientTest.closeCompletesQueuedFuturesBeforeTheirWorkerEverRuns` | `HttpShutdownTest.closeCompletesQueuedFuturesBeforeTheirWorkerEverRuns` |
| `HttpJevClientTest.callRacingCloseNeverEscapesShutdown` | `HttpShutdownTest.callRacingCloseNeverEscapesShutdown` |
| `HttpJevClientTest.publicationTimeoutMakesCloseThrowAfterShuttingDownOwnedResources` | `HttpShutdownTest.publicationTimeoutMakesCloseThrowAfterShuttingDownOwnedResources` |
| `HttpJevClientTest.interruptedPublicationWaitMakesCloseThrowAndReassertTheFlag` | `HttpShutdownTest.interruptedPublicationWaitMakesCloseThrowAndReassertTheFlag` |
| `HttpJevClientTest.concurrentCloseObservesTheFirstClosersOutcome` | `HttpShutdownTest.concurrentCloseObservesTheFirstClosersOutcome` |
| `HttpJevClientTest.repeatedCloseAfterSuccessfulShutdownReturnsNormally` | `HttpShutdownTest.repeatedCloseAfterSuccessfulShutdownReturnsNormally` |
| `HttpJevClientTest.interruptedConcurrentCloserThrowsAndReassertsTheFlag` | `HttpShutdownTest.interruptedConcurrentCloserThrowsAndReassertsTheFlag` |
| `HttpJevClientTest.concurrentCloserSpendsAtMostOneGracePlusPublicationBudget` | `HttpShutdownTest.concurrentCloserSpendsAtMostOneGracePlusPublicationBudget` |
| `HttpJevClientTest.concurrentCloserReturnsNormallyWhenPublicationArrivesBeforeItsDeadline` | `HttpShutdownTest.concurrentCloserReturnsNormallyWhenPublicationArrivesBeforeItsDeadline` |
| `HttpJevClientTest.concurrentCloserInterruptedDuringPublicationRemainderThrows` | `HttpShutdownTest.concurrentCloserInterruptedDuringPublicationRemainderThrows` |
| `HttpJevClientTest.rejectedExecutorFailsTheFutureCleanly` | `HttpPublicationTest.rejectedExecutorFailsTheFutureCleanly` |
| `HttpJevClientTest.queuedCallExpiresAtTheDeadlineWhileTheExecutorIsStillBlocked` | `HttpPublicationTest.queuedCallExpiresAtTheDeadlineWhileTheExecutorIsStillBlocked` |
| `HttpJevClientTest.blockingCallbackDoesNotBlockOtherDeadlines` | `HttpPublicationTest.blockingCallbackDoesNotBlockOtherDeadlines` |
| `HttpJevClientTest.closeIsBoundedDespiteBlockingCallback` | `HttpPublicationTest.closeIsBoundedDespiteBlockingCallback` |
| `HttpJevClientTest.cancelPreventsRetryWhileTheCancellationCallbackIsBlocked` | `HttpPublicationTest.cancelPreventsRetryWhileTheCancellationCallbackIsBlocked` |
| `HttpJevClientTest.closeWaitsForPublicationButNotForCallbacks` | `HttpPublicationTest.closeWaitsForPublicationButNotForCallbacks` |
| `HttpJevClientTest.resultsAreAlwaysTerminalWhenCloseReturns` | `HttpPublicationTest.resultsAreAlwaysTerminalWhenCloseReturns` |
| `HttpJevClientTest.completionsArePublishedOnSdkVirtualThreadsForEveryOutcome` | `HttpPublicationTest.completionsArePublishedOnSdkVirtualThreadsForEveryOutcome` |
| `HttpJevClientTest.rejectedDeliveryAndCloseInTheHandoffGapStillPublishOnVirtualThreads` | `HttpPublicationTest.rejectedDeliveryAndCloseInTheHandoffGapStillPublishOnVirtualThreads` |
| `HttpJevClientTest.loggingHonoursTheClientLevelAndRedactsCredentials` | `HttpDiagnosticsTest.loggingHonoursTheClientLevelAndRedactsCredentials` |
| `HttpJevClientTest.configToStringRedactsCredentials` | `HttpDiagnosticsTest.configToStringRedactsCredentials` |
| `HttpJevClientTest.debugRetryLogsCarryNoBodyText` | `HttpDiagnosticsTest.debugRetryLogsCarryNoBodyText` |
| `HttpJevClientTest.unknownAnswerAndObserverWarningsHonourEachClientsLevel` | `HttpDiagnosticsTest.unknownAnswerAndObserverWarningsHonourEachClientsLevel` |
| `HttpJevClientTest.twoClientsWithDifferentLevelsFilterIndependently` | `HttpDiagnosticsTest.twoClientsWithDifferentLevelsFilterIndependently` |
| `HttpJevClientTest.terminalEventNeverPrecedesAnAttemptThatStarted` | `HttpObserverLifecycleTest.terminalEventNeverPrecedesAnAttemptThatStarted` |
| `CallObserverTest.blockedOnCallDoesNotStallOtherDeadlinesNorTheFailedResult` | `CallObserverLifecycleTest.blockedOnCallDoesNotStallOtherDeadlinesNorTheFailedResult` |
| `CallObserverTest.blockedOnCallDoesNotUnboundClose` | `CallObserverLifecycleTest.blockedOnCallDoesNotUnboundClose` |
| `CallObserverTest.blockedOnAttemptAndOnCallDoNotDelaySuccessOrCancellation` | `CallObserverLifecycleTest.blockedOnAttemptAndOnCallDoNotDelaySuccessOrCancellation` |
| `CallObserverTest.eventsOfOneCallArriveInOrderOnVirtualThreads` | `CallObserverLifecycleTest.eventsOfOneCallArriveInOrderOnVirtualThreads` |
| `CallObserverTest.attemptStartedBeforeTerminationIsDeliveredBeforeTheTerminalEvent` | `CallObserverLifecycleTest.attemptStartedBeforeTerminationIsDeliveredBeforeTheTerminalEvent` |
| `CallObserverTest.requestConstructionFailureIsObservedAsAnErrorCall` | `CallObserverLifecycleTest.requestConstructionFailureIsObservedAsAnErrorCall` |
