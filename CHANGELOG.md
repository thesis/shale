# Change Log
All notable changes to this project will be documented in this file.

Note: all versions before 1.0 might include breaking API changes. After 1.0,
this project will adhere to [Semantic Versioning](http://semver.org/).

## [0.2.2] - 2016-05-17
### Added
- Optionally run an nrepl server.
- Added a simple management interface to for sessions and nodes. Currently
sessions can be reserved, unreserved, and destroyed.

### Changed
- Upgraded to Clojure 1.7.
- Refactored to manage lifecycle with [component].
- Soft delete sessions so they don't hang.
- Use the `reserve` param to reserve sessions (instead of `reserve_after_create`)

## [0.2.1] - 2015-09-20
### Changed
- Optionally accept a URL root for all shale client functions.

## [0.2.0] - 2015-09-17
### Added
- Session logging [#22]
- Improved test coverage beyond integration tests.
- Added the `nodes` endpoint to the Clojure client.
- Clients can optionally specify host nodes by ID at session creation.

### Changed
- Refresh sessions in parallel, minimizing lag [#26]
- Use Prismatic's schema for data validation.
- Separate "webdriver" and "session" IDs. Clients should use session IDs to
refer to shale sessions, but webdriver IDs to actually construct and interact
with remote webdrivers.
- Initial implementation of a more complete session get-or-create [#24][predicate
logic]. While the details of this ongoing effort are worked out, the old
get-or-create will still be supported.
- Extracted shared internal Redis model logic between nodes and sessions.

### Fixed
- The broken test suite [#25]
- A bug in session reservation logic [#24]
- A bug executing JS from the Clojure client [JS-bug]
- MalformedURLException when node lists were empty [#23]
- Recover when a webdriver dies [#21]
- Kill any unknown sessions on nodes to prevent "session leak" resource
exhaustion issues. [#44],[#47]
- Nasty [race condition][node-race] initializing and refreshing the node pool.

## [0.1.1] - 2014-10-14
### Added
- SCM details to Clojars.

### Changed
- Improved documentation.

## [0.1.0] - 2014-10-14
### Added
- A Clojure client.

### Changed
- Rewrote Shale in Clojure.
- Extracted the Python client to its own repo.

### Fixed
- A number of performance issues related to poor Python's lackluster
concurrency story.

[unreleased]: https://github.com/cardforcoin/shale/compare/shale-0.2.2...HEAD
[0.2.2]: https://github.com/cardforcoin/shale/compare/shale-0.2.1...shale-0.2.2
[0.2.1]: https://github.com/cardforcoin/shale/compare/v0.2.0...shale-0.2.1
[0.2.0]: https://github.com/cardforcoin/shale/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/cardforcoin/shale/compare/3fc098ef45d...v0.1.1
[0.1.0]: https://github.com/cardforcoin/shale/compare/80d62f3dcc3c...3fc098ef45d
[#24]: https://github.com/cardforcoin/shale/issues/24
[#22]: https://github.com/cardforcoin/shale/issues/22
[#25]: https://github.com/cardforcoin/shale/issues/25
[JS-bug]: https://github.com/cardforcoin/shale/commit/25daad9aadb37d34482c76aa0f4ee57f4d93828b
[#24]: https://github.com/cardforcoin/shale/issues/23
[#21]: https://github.com/cardforcoin/shale/issues/21
[#26]: https://github.com/cardforcoin/shale/issues/26
[#44]: https://github.com/cardforcoin/shale/issues/44
[#47]: https://github.com/cardforcoin/shale/issues/47
[node-race]: https://github.com/cardforcoin/shale/commit/4ab8b417d0c724f0af269e34b8d4c611a31c6c09
[component]: https://github.com/stuartsierra/component
