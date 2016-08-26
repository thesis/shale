# shale

Shale, a Clojure-backed Selenium hub replacement

[![Circle CI](https://circleci.com/gh/cardforcoin/shale/tree/master.png?style=badge)](https://circleci.com/gh/cardforcoin/shale/tree/master)

[![Clojars Project](http://clojars.org/shale/latest-version.svg)](http://clojars.org/shale)

## Progress

Shale is a lightweight replacement for a Selenium hub. We've found hubs to be
more trouble than they're worth operationally, but we still need some of the
management features.

Currently shale lets you get, create, and delete webdriver sessions, and
maintains "reservation" and "tag" metadata to make querying against sessions
easy.

Nodes can be discovered via the AWS API, a list of URLs, or through a custom
`INodePool` implementation. See the example config for details.

## Running

Clone the repo

```sh
git clone git@github.com:cardforcoin/shale.git
```

In development, you can run the service with `lein`.

```sh
lein run
# OR, run it in a REPL
lein repl
> (init)
> (start)
INFO [shale.configurer] Loaded shale config...
{:node-list ["http://localhost:4444/wd/hub" "http://localhost:4443/wd/hub"],
 :node-max-sessions 6,
 :port 5000,
 :webdriver-timeout 3000,
 :start-webdriver-timeout 5000}
INFO [shale.nodes] - Starting the node pool...
INFO [shale.sessions] - Starting session pool...
INFO [shale.core] - Starting Jetty...
```

To deploy the service, make a jar and run that.

```sh
lein uberjar
java -jar target/shale-0.3.0-SNAPSHOT-standalone.jar
```

## Configuration

By default, a config file- `config.clj`- is expected on the classpath.
`example-config.clj` shows the various options. If you'd like to provide a full
path to an alternative config file, set an environment variable `CONFIG_FILE`
with the path, or include the option using another method supported by
[environ][environ].

All grid nodes should be configured so that they don't attempt to register with
a hub (`register:false` in the node config), and should be accessible via the
machine running shale.

[environ]: https://github.com/weavejester/environ

## Examples

Session creation, selection, and modification uses an S-expression inspired
syntax.

### curl

#### Get or create a new session

```bash
curl -d '{"require": ["browser_name", "phantomjs"]}' -XPOST \
     http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

```json
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": []
}
```

#### List all the active sessions

```bash
curl http://localhost:5000/sessions/
```

```json
[{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": []
}]
```

#### Create a new session with tags

```bash
curl -d '{"create": {"browser_name":"phantomjs", "tags":["walmart"]}}' \
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

```json
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart"]
}
```

#### Get or create a new reserved session with tags

```bash
curl -d '{"require": ["and [["browser_name", "phantomjs"] ["tag", "walmart"]]],
          "modify": [["reserve", true]]}' \
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

```json
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": true,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart"]
}
```

#### Create a session on a particular node

```bash
curl -d '{"create": {"browser_name":"phantomjs", "node":{"id": "<node id>"}}}' \
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

#### Create a Chrome session using a proxy (manually)

You can create sessions with custom options, like proxy settings, disabling
local storage, or any other settings exposed by Selenium's
`DesiredCapabilities`.

```bash
curl -d '{"create": {"browser_name":"chrome", "extra_desired_capabilities": {"chromeOptions": {"args": ["--proxy-server=socks5://<host>:<port>", "--disable-plugins","--disable-local-storage"]}}}' \
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

Note, though, that there are better ways to manage session proxies!

#### Unreserve a session and add a tag

PATCH accepts an array of modifications, including `change_tag`, `reserve`, and
`go_to_url`.

```bash
curl -d '[["change_tag", {"action": "add", "tag":"walmart"}], ["reserve", true]]' \
     -XPATCH http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db \
     --header "Content-Type:application/json"
```

```json
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart", "logged-in"]
}
```

#### Delete a session

Note that this will de-allocate the Selenium driver.

```bash
curl -XDELETE http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db
```

```
true
```

#### Delete all sessions

```bash
curl -XDELETE http://localhost:5000/sessions/
```

```json
true
```

### Proxies

Shale also includes its own proxy management that allows proxies to be shared
between sessions.

An initial proxy list can be provided in the config.

#### Add a new proxy

```bash
curl -XPOST http://localhost:5000/proxies/ \
     -d '{
            "public_ip": "8.8.8.8",
            "private_host_and_port": "127.0.0.1:1234",
            "type":"socks5"
            "shared":true
         }' \
     --header "Content-Type:application/json"
```

```json
{
    "id":"f7c64a2c-595d-434c-80f0-15c9751ddcc8",
    "public_ip":"127.0.0.1",
    "private_host_and_port":"128.0.0.1:1234",
    "type":"socks5",
    "active":true,
    "shared":true
}
```
A proxy with `"shared": false` can only be used by sessions that reference it
by ID. Those with `"shared": true` are candidates for use by other sessions.

The `public_ip` is an optional attribute to keep track of a proxies
public-facing IP. This can be useful if you're testing public sites on the
internet, and have an IP whitelist.

Currently, only SOCKS proxies are supported.

#### List all proxies

```bash
curl http://localhost:5000/proxies/
```

```json
[
    {
        "id":"f7c64a2c-595d-434c-80f0-15c9751ddcc8",
        "public_ip":"8.8.8.8",
        "private_host_and_port":"127.0.0.1:1234",
        "type":"socks5",
        "active":true,
        "shared":true
    }
]
```

#### Create a Chrome session with a new proxy

```bash
curl -d '{"create": {"browser_name":"chrome", "proxy": {"type": "socks5", "private_host_and_port": "<host>:<port>", "shared": true}}}'
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

If the proxy hasn't been seen before, it will be recorded as a candidate for use
by other sessions. To avoid that behavior, set `"shared": false`.

#### Get or create a Chrome session with an existing proxy

```bash
curl -d '{"require": ["and" [["browser_name", "chrome"] ["proxy", ["id", "f7c64a2c-595d-434c-80f0-15c9751ddcc8"]]]]}'
     -XPOST http://localhost:5000/sessions/ \
     --header "Content-Type:application/json"
```

### Clojure

The Clojure client returns functional web drivers using `clj-webdriver`,
and includes a macro to make working with drivers easier.

Here's an example of how to get-or-create, reserve, use, and release a driver
using the `with-webdriver*` macro, inspired by the `clj-webdriver` examples.

```clojure
;; Log into Github
;;
(use '[shale.client :only [with-driver]])
(use 'clj-webdriver.taxi)
(with-webdriver* {:browser-name :firefox :tags ["github"]}
  (to "https://github.com")
  (click "a[href*='login']")
  (input-text "#login_field" "your_username")
  (-> "#password"
    (input-text "your_password")
    submit))
```

See the [clj-webdriver docs][clj-webdriver] and the client source for more details.

### Python

There is also a Python client with its [own examples and documentation][shale-python].

[clj-webdriver]: http://semperos.github.io/clj-webdriver/

[shale-python]: https://github.com/cardforcoin/shale-python
