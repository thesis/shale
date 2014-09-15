import json
import requests
from nose.tools import with_setup, ok_, eq_

import shale
import shale.client

client = shale.client.default_client

def teardown():
    # kill all open sessions
    resp = requests.get('http://localhost:5000/sessions')
    resp_data = json.loads(resp.content)
    resps = [requests.delete('http://localhost:5000/sessions/{id}'.format(**r))
             for r in resp_data]


@with_setup(teardown=teardown)
def test_create():
    browser_details = client.create_browser(browser_name='phantomjs')
    ok_('id' in browser_details)
    ok_('browser_name' in browser_details)
    ok_('tags' in browser_details)
    eq_(browser_details.get('reserved'), False)

    tags = ['test1','test2']
    browser_details = client.get_or_create_browser(browser_name='phantomjs', tags=tags)
    eq_(set(browser_details.get('tags',[])), set(tags))


def create_clients(kwarg_dicts):
    for d in kwarg_dicts:
        d = dict(d)
        d['force_create'] = True
        client.get_or_create_browser(**d)


def setup_logged_in_clients():
    browser_specs = [
        {'browser_name':'phantomjs', 'tags':['logged-in']},
        {'browser_name':'phantomjs', 'tags':['logged-out']},
    ]
    create_clients(browser_specs)


@with_setup(setup=setup_logged_in_clients, teardown=teardown)
def test_get_or_create():
    # test that get_or_create doesn't create an unnecessary session
    browser = client.get_or_create_browser(tags=['logged-in'])
    eq_(len(client.running_browsers()), 2)

    # and that it does create a new session with a new tag
    browser = client.get_or_create_browser(tags=['some-other-state'])
    eq_(len(client.running_browsers()), 3)


@with_setup(setup=setup_logged_in_clients, teardown=teardown)
def test_context_manager():
    with client.browser(browser_name='phantomjs',tags=['logged-in']) as b1:
        with client.browser(browser_name='phantomjs',tags=['logged-out']) as b2:
            pass

# TODO find a way to test the UA -  current approach doesn't seem to work
#@with_setup(teardown=teardown)
#def test_extra_cap():
#    ua_key = 'phantomjs.page.settings.userAgent'
#    ua = 'test-user-agent'
#    browser = client.get_or_create_browser(
#            browser_name='phantomjs', extra_desired_capabilities={ua_key:ua},
#            reserve=True)
#    browser_ua = browser.execute_script("return navigator.userAgent;")
#    eq_(ua, browser_ua)


@with_setup(teardown=teardown)
def test_running_browsers():
    browser_specs = [
        {'browser_name':'phantomjs', 'tags':['logged-in']},
        {'browser_name':'phantomjs', 'tags':[], 'reserve':True},
    ]
    create_clients(browser_specs)

    browsers = client.running_browsers()

    eq_(len(browsers), len(browser_specs))
    ok_(any(b1['tags'] == b2['tags']
            for b1 in browser_specs
            for b2 in browsers))
    eq_(1, len(list(b for b in browsers if b['reserved'])))
