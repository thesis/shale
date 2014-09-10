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
