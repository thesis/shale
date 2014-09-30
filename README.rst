shale
=====

Shale, a Clojure-backed Selenium hub replacement


Progress
--------

Shale is a lightweight replacement for a Selenium hub. We've found hubs to be
more trouble than they're worth operationally, but we still need some of the
management features.

Currently shale let's you get, create, and delete webdriver sessions, as well as
managing which sessions are currently reserved or tagged. It manages and removes
dead sessions, and if you provide an `INodePool` implementation, it can be
easily integrated with a cloud provider or other infrastructure for node
discovery and provisioning.

Running
-------

Clone the repo

.. code:: sh

    git clone git@github.com:cardforcoin/shale.git

In development, you can run the service with `lein`.

.. code:: sh

    lein ring server

To deploy, make a jar and run that.

.. code:: sh

    lein uberjar
    java -jar target/shale-0.1.0-SNAPSHOT-standalone.jar

Examples
--------

curl
~~~~

.. code::

    $ curl -d '{"browser_name":"phantomjs"}' -XPOST \
           http://localhost:5000/sessions/ \
           --header "Content-Type:application/json"
    {
      "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
      "reserved": false,
      "node": "http://localhost:5555/wd/hub",
      "browser_name": "phantomjs",
      "tags": []
    }

List all the active sessions

.. code::

    $ curl http://localhost:5000/sessions/
    [{
      "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
      "reserved": "False",
      "node": "http://localhost:5555/wd/hub",
      "browser_name": "phantomjs",
      "tags": []
    }]

Get or create a new session with tags

.. code::

    $ curl -d '{"browser_name":"phantomjs", "tags":["walmart"]}' \
           -XPOST http://localhost:5000/sessions/ \
           --header "Content-Type:application/json"
    {
      "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
      "reserved": false,
      "node": "http://localhost:5555/wd/hub",
      "browser_name": "phantomjs",
      "tags": ["walmart"],
      "reserved": false
    }

Get or create a new reserved session with tags

.. code::

    $ curl -d '{"browser_name":"phantomjs", "tags":["walmart"]}' \
           -XPOST http://localhost:5000/sessions/?reserve=True \
           --header "Content-Type:application/json"
    {
      "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
      "reserved": false,
      "node": "http://localhost:5555/wd/hub",
      "browser_name": "phantomjs",
      "tags": ["walmart"],
      "reserved": true
    }

Unreserve a session and add a tag

.. code::

    $ curl -d '{"tags":["walmart", "logged-in"], "reserved":false}' \
           -XPUT http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db \
           --header "Content-Type:application/json"
    {
      "id": "05e9229d-356b-46a3-beae-f8ab02cea7db",
      "reserved": "True",
      "node": "http://localhost:5555/wd/hub",
      "browser_name": "phantomjs",
      "tags": ["walmart", "logged-in"]
    }

Delete a session. Note that this will de-allocate the Selenium driver.

.. code::

    $ curl -XDELETE http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db
    true

There is also a Python client with its `own examples and documentation`__.

__ https://github.com/cardforcoin/shale-python
