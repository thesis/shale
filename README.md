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
lein ring server
```

To deploy the service, make a jar and run that.

```sh
lein uberjar
java -jar target/shale-0.1.0-SNAPSHOT-standalone.jar
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

### curl

Get or create a new session.

```sh
$ curl -d '{"browser_name":"phantomjs"}' -XPOST \
       http://localhost:5000/sessions/ \
       --header "Content-Type:application/json"
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": []
}
```

List all the active sessions

```sh
$ curl http://localhost:5000/sessions/
[{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": "False",
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": []
}]
```

Get or create a new session with tags

```sh
$ curl -d '{"browser_name":"phantomjs", "tags":["walmart"]}' \
       -XPOST http://localhost:5000/sessions/ \
       --header "Content-Type:application/json"
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart"],
  "reserved": false
}
```

Get or create a new reserved session with tags

```sh
$ curl -d '{"browser_name":"phantomjs", "tags":["walmart"]}' \
       -XPOST http://localhost:5000/sessions/?reserve=True \
       --header "Content-Type:application/json"
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": false,
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart"],
  "reserved": true
}
```

Unreserve a session and add a tag

```sh
$ curl -d '{"tags":["walmart", "logged-in"], "reserved":false}' \
       -XPUT http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db \
       --header "Content-Type:application/json"
{
  "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
  "reserved": "True",
  "node": {"url":"http://localhost:5555/wd/hub", "id":"2c28f0f2-e479-4501-a05d-a0991793abd7"},
  "browser_name": "phantomjs",
  "tags": ["walmart", "logged-in"]
}
```

Delete a session. Note that this will de-allocate the Selenium driver.

```sh
$ curl -XDELETE http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db
true
```

### Clojure

The Clojure client returns functional web drivers using `clj-webdriver`,
and includes a macro to make working with drivers easier.

Here's an example of how to get-or-create, reserve, use, and release a driver
using the `with-webdriver*` macro, inspired by the `clj-webdriver` examples.

```clojure
;; Log into Github
;;
(use '[shale.client :only [with-driver])
(use 'clj-webdriver.taxi)
(with-webdriver* {:browser-name :firefox :tags [\"github\"]}
  (to \"https://github.com\")
  (click \"a[href*='login']\")
  (input-text \"#login_field\" \"your_username\")
  (-> \"#password\"
    (input-text \"your_password\")
    submit))
```

See the [clj-webdriver docs][clj-webdriver] and the client source for more details.

### Python

There is also a Python client with its [own examples and documentation][shale-python].


[clj-webdriver]: http://semperos.github.io/clj-webdriver/

[shale-python]: https://github.com/cardforcoin/shale-python
