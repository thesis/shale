shale
=====

A Flask-backed REST API to manage Selenium sessions.

.. pypi - Everything below this line goes into the description for PyPI.


Progress
--------

Right now, the service is basically a replacement for a Selenium hub. We've
found hubs to be more trouble than they're worth operationally, but we still
need some of the management features.

Currently shale let's you get, create, and delete webdriver sessions, as well as
managing which sessions are currently reserved or tagged. Keep-alive pings have
have been implemented, and it's easy to integrate with a cloud provider (eg,
AWS) to auto-scale nodes by implementing a custom `NodePool`.

Running
-------

Clone the repo

.. code:: sh

    git clone git@github.com:cardforcoin/shale.git

Something

.. code:: sh

    lein run

Something else

.. code:: sh

    lein ring server

Configuration
-------------

To provide a configuration module, include a  variable `SHALE_SETTINGS` in your
environment. Current options include the basic config provided by Flask, as well
as a configurable `HUB_POOL` for dynamic hub selection.

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

python
------

Get a client

.. code:: python

    >>> import shale.client
    >>> client = shale.client
    >>> # or, use a custom client
    >>> client = shale.client.Client('http://my-shale-host:5000')

List all running webdrivers.

.. code:: python

    >>> client.running_browsers()
    ({u'browser_name': u'phantomjs',
      u'node': u'http://localhost:5555/wd/hub',
      u'id': u'31027408-3e45-4d27-9770-ba1a26953dfc',
      u'reserved': True,
      u'tags': [u'target', u'linux']},)

Reserve a webdriver.

.. code:: python

    >>> browser = client.reserve_browser('31027408-3e45-4d27-9770-ba1a26953dfc')
    <shale.client.ClientResumableRemote at 0x1d92fd0>

... and release it.

.. code:: python

    >>> browser.release()

There's a handy context manager for reserving & releasing webdrivers.

.. code:: python

    >>> with client.browser(browser_name='chrome', tags=['logged-in']) as browser:
    ...     # do yo thang
    ...     pass
    ...
    >>> with client.browser(session_id='123...') as browser:
    ...     # do yo thang with a particular browser session
    ...     pass

You can also force create a new remote webdriver.

.. code:: python

    >>> browser = client.create_browser()
