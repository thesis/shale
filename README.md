shale
=====

A Flask-backed REST API to manage Selenium sessions.

# Progress

Right now, all the service does is manage metadata on Selenium usage.
Integrating with AWS to auto-scale hubs and nodes, as well as keep-alive pings
and other supervision, are being developed.

# Running

Clone the repo and install the requirements

```sh
git clone git@github.com:cardforcoin/shale.git
pip install -r requirements.txt
```

In development, you can run the Flask dev server.

```sh
python wsgi.py
```

In production, use gunicorn.

```sh
gunicorn -b 127.0.0.1:5000 wsgi:app -t 120 --log-file -
```

# Examples

## curl

```sh
> curl -d '{"browser_name":"phantomjs"}' -XPOST http://localhost:5000/sessions/ --header "Content-Type:application/json"
{"id": "05e9229d-356b-46a3-beae-f8ab02cea7db", "reserved": false, "hub": "localhost:4444", "browser_name": "phantomjs", "tags": []}

> # list all the active sessions
> curl http://localhost:5000/sessions/
[{"id": "05e9229d-356b-46a3-beae-f8ab02cea7db", "reserved": "False", "hub": "localhost:4444", "browser_name": "phantomjs", "tags": []}]

> # create a new reserved session with tags
> curl -d '{"browser_name":"phantomjs", "tags":["walmart"], "reserved":true}' -XPOST http://localhost:5000/sessions/ --header "Content-Type:application/json"
{"id": "05e9229d-356b-46a3-beae-f8ab02cea7db", "reserved": false, "hub": "localhost:4444", "browser_name": "phantomjs", "tags": ["walmart"], "reserved":true}

> # unreserve a session and add a tag
> curl -d '{"tags":["walmart", "logged-in"], "reserved":false}' -XPUT http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db --header "Content-Type:application/json"
{"id": "05e9229d-356b-46a3-beae-f8ab02cea7db", "reserved": "True", "hub": "localhost:4444", "browser_name": "phantomjs", "tags": ["walmart", "logged-in"]}

> # delete a session. note that this will de-allocate the Selenium driver
> curl -XDELETE http://localhost:5000/sessions/05e9229d-356b-46a3-beae-f8ab02cea7db
true
```

## python

Get a client

```python
> import shale.client
> client = shale.client
> # or, use a custom client
> client = shale.client.Client('http://my-shale-host:5000')
```

List all running webdrivers.

```python
> client.running_browsers()
({u'browser_name': u'phantomjs',
  u'hub': u'localhost:4444',
  u'id': u'31027408-3e45-4d27-9770-ba1a26953dfc',
  u'reserved': True,
  u'tags': [u'target', u'linux']},)
```

Reserve a webdriver.

```python
> browser = client.reserve_browser('31027408-3e45-4d27-9770-ba1a26953dfc')
<shale.client.ClientResumableRemote at 0x1d92fd0>
```

... and release it.

```python
> browser.release()
```

There's a handy context manager for reserving & releasing webdrivers.

```python
with client.browser(id) as browser:
    # do yo thang
    pass
```

Finally, if there's not a good candidate running, you can create and reserve
a new remote webdriver.

```python
browser = client.create_browser(reserve=True)
```
