# Person screen test fixture fix

Updated the shared `PersonServiceImplTest` setup to use lenient Mockito stubs for the optional viewer lookup, followed-person lookup, and default diary/list media queries. These stubs are unused in invalid-id, malformed-aggregate, and empty-filmography paths.

Verification: `MAVEN_OPTS` set to the requested local Maven repository and `user.home`; `mvnw.cmd test -Dtest=PersonServiceImplTest` passed (14 tests, 0 failures, 0 errors, 0 skipped).
