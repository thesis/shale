import json
import requests
from contextlib import contextmanager

from .webdriver import ResumableRemote

__all__ = ['Client', 'default_client ', 'reserve_browser', 'release_browser',
            'browser', 'candidates']


class ClientResumableRemote(ResumableRemote):
    def __init__(self, client, *args, **kwargs):
        super(ClientResumableRemote, self).__init__(*args, **kwargs)
        original_execute = self.command_executor.execute
        def releasable_execute(*args, **kwargs):
            if self.released:
                raise ValueError('The associated browser has been released, and'
                                 ' should no longer be used.')
            return original_execute(*args, **kwargs)
        self.command_executor.execute = releasable_execute
        self.client = client
        self.released = False

    def release(self):
        self.client.release_browser(self)
        self.released = True

    @property
    def tags(self):
        return tuple(
            self.client.browser_metadata(self.session_id).get('tags', []))

    @tags.setter
    def tags(self, new_tags):
        self.client.set_tags(self.session_id, new_tags)


class Client(object):
    def __init__(self, url_root='http://localhost:5000'):
        self.url_root = url_root
        self.headers = {'Content-type': 'application/json'}

    def create_browser(self, browser_name='phantomjs', tags=None,
                       hub=None, reserve=False):

        tags = tags or []

        data = {
            'hub': hub,
            'tags': tags,
            'reserved': reserve,
            'browser_name': browser_name,
        }
        resp = requests.post('{}/sessions/'.format(self.url_root),
                data=json.dumps(data), headers=self.headers)
        resp_data = json.loads(resp.content)
        if reserve:
            return ClientResumableRemote(client=self,
                    session_id=resp_data['id'], hub=resp_data['hub'])
        return resp_data['id']

    def reserve_browser(self, session_id):
        resp = requests.put('{}/sessions/{}'.format(self.url_root, session_id),
                data=json.dumps({'reserved': True}), headers=self.headers)
        resp_data = json.loads(resp.content)
        return ClientResumableRemote(client=self, session_id = resp_data['id'],
                hub=resp_data['hub'])

    def release_browser(self, browser):
        requests.put('{}/sessions/{}'.format(self.url_root, browser.session_id),
                data=json.dumps({'reserved': False}), headers=self.headers)

    def destroy_browser(self, browser=None, session_id=None):
        to_delete = session_id or getattr(browser, 'session_id')
        requests.delete('{}/sessions/{}'.format(self.url_root, to_delete))

    def running_browsers(self, reserved=None, browser_name=None,
            with_tags=None, without_tags=None):

        with_tags = with_tags or []
        without_tags = without_tags or []

        resp = requests.get('{}/sessions/'.format(self.url_root))
        browser_data = json.loads(resp.content)
        browser_data = (b for b in browser_data
                        if (reserved is None
                            or (b.get('reserved', False) == reserved)))
        browser_data = (b for b in browser_data
                        if (browser_name is None
                            or (b.get('browser_name', None) == browser_name)))
        browser_data = (b for b in browser_data
                        if (not with_tags
                            or (set(with_tags) <= set(b.get('tags', [])))))
        browser_data = (b for b in browser_data
                        if (not without_tags
                            or (set(b.get('tags', [])).isdisjoint(set(without_tags)))))
        return tuple(browser_data)

    def browser_metadata(self, session_id):
        resp = requests.get('{}/sessions/{}'.format(self.url_root, session_id))
        return json.loads(resp.content)

    def set_browser_tags(self, session_id, tags=None):

        tags = tags or []

        requests.put('{}/sessions/{}'.format(self.url_root, session_id),
            data=json.dumps({'tags': tags}), headers=self.headers)

    @contextmanager
    def browser(self, *args, **kwargs):
        browser = self.reserve_browser(*args, **kwargs)
        try:
            yield browser
        finally:
            browser.release()


default_client = Client()

reserve_browser = default_client.reserve_browser

release_browser = default_client.reserve_browser

browser = default_client.browser

running_browsers = default_client.running_browsers
