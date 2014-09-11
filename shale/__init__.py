from __future__ import absolute_import

import os
import json
from decorator import decorator
from redis import ConnectionPool, Redis, WatchError
from selenium.webdriver.common.desired_capabilities import DesiredCapabilities
from selenium.common.exceptions import WebDriverException

from flask import request, Response
from flask.views import MethodView
from werkzeug.contrib.fixers import ProxyFix
from werkzeug.exceptions import NotFound

from .webdriver import ResumableRemote
from .hubs import DefaultHubPool
from .utils import permit, merge, retry

from flask import Flask
app = Flask(__name__)

__all__ = ['app']

app.debug = True
app.config.update(
    DEBUG=True,
    HUB_POOL=DefaultHubPool(),
)

if 'SHALE_SETTINGS' in os.environ:
    app.config.from_envvar('SHALE_SETTINGS')

REDIS_KEY_PREFIX = '_shale'
SESSION_SET_KEY = '{}_session_set'.format(REDIS_KEY_PREFIX)
SESSION_KEY_TEMPLATE = REDIS_KEY_PREFIX + '_session_{}'
SESSION_TAGS_KEY_TEMPLATE = REDIS_KEY_PREFIX + '_session_{}_tags'

pool = ConnectionPool(host='localhost', port=6379, db=0)


def returns_json(func):
    def inner(*args, **kwargs):
        status=200
        data = {}
        try:
            data = json.dumps(func(*args, **kwargs))
        except NotFound:
            status = 404
        return Response(data, content_type="application/json", status=status)
    return inner


class RedisView(object):
    def dispatch_request(self, *args, **kwargs):
        self.redis = Redis(connection_pool=pool)
        return super(RedisView, self).dispatch_request(*args, **kwargs)


class SessionAPI(RedisView, MethodView):

    decorators = [returns_json]

    def get(self, session_id):
        if session_id is None:
            return self.view_models()
        # get the specified session or 404
        view_model = self.view_model(session_id)
        if view_model is None:
            raise NotFound()
        return view_model

    def post(self):
        data = json.loads(request.data) if request.data else {}

        defaults = {
            'browser_name': 'firefox',
            'hub': 'localhost:4444',
            'tags': [],
            'reserved': False,
        }
        permitted = permit(data,
                ['browser_name', 'hub', 'tags', 'reserved', 'current_url'])
        cleaned = merge(permitted, defaults)
        # if hub was set to None, choose one
        cleaned['hub'] = cleaned['hub'] or \
                app.config['HUB_POOL'].get_hub(**cleaned)

        cap = {
            'chrome': DesiredCapabilities.CHROME,
            'firefox': DesiredCapabilities.FIREFOX,
            'phantomjs': DesiredCapabilities.PHANTOMJS,
        }.get(cleaned['browser_name'])

        wd = ResumableRemote(
            command_executor='http://{}/wd/hub'.format(cleaned['hub']),
            desired_capabilities=cap)
        cleaned['hub'] = wd.command_executor._url
        if 'current_url' in cleaned:
            # we do this in JS to prevent a blocking call
            wd.execute_script(
                'window.location = "{}";'.format(cleaned['current_url']))
        with self.redis.pipeline() as pipe:
            pipe.sadd(SESSION_SET_KEY, wd.session_id)
            session_key = SESSION_KEY_TEMPLATE.format(wd.session_id)
            permitted = permit(
                cleaned, ['browser_name', 'hub', 'reserved', 'current_url'])
            for key, value in permitted.items():
                pipe.hset(session_key, key, value)
            tags_key = SESSION_TAGS_KEY_TEMPLATE.format(wd.session_id)
            pipe.delete(tags_key)
            for tag in cleaned['tags']:
                pipe.sadd(tags_key, tag)
            pipe.execute()
        return self.view_model(wd.session_id, data=cleaned)

    def put(self, session_id):
        data = json.loads(request.data) if request.data else {}
        cleaned = permit(data, ['tags', 'reserved'])

        with self.redis.pipeline() as pipe:
            tags_key = SESSION_TAGS_KEY_TEMPLATE.format(session_id)
            if 'tags' in cleaned:
                pipe.delete(tags_key)
                for tag in cleaned['tags']:
                    pipe.sadd(tags_key, tag)
                if 'reserved' in cleaned:
                    pipe.hset(SESSION_KEY_TEMPLATE.format(session_id),
                              'reserved', cleaned['reserved'])
            pipe.execute()
            return self.view_model(session_id)


    def delete(self, session_id):
        wd = ResumableRemote(session_id=session_id)
        try:
            wd.quit()
        except WebDriverException:
            pass
        with self.redis.pipeline() as pipe:
            pipe.srem(SESSION_SET_KEY, session_id)
            pipe.delete(SESSION_KEY_TEMPLATE.format(session_id))
            pipe.delete(SESSION_TAGS_KEY_TEMPLATE.format(session_id))
            pipe.execute()
        return True

    def view_model(self, session_id, data=None, should_retry=True):
        def get_data():
            ret = None
            with self.redis.pipeline() as pipe:
                session_key = SESSION_KEY_TEMPLATE.format(session_id)
                tags_key = SESSION_TAGS_KEY_TEMPLATE.format(session_id)
                pipe.watch(session_key)
                pipe.watch(tags_key)
                if pipe.exists(session_key):
                    ret = pipe.hgetall(session_key)
                    ret['tags'] = list(pipe.smembers(tags_key))
                pipe.execute()
            return ret
        if data is None:
            if should_retry:
                data = retry(get_data, exception_type=WatchError, raises=False)
            else:
                data = get_data()
        if data and 'reserved' in data:
            data.update(**{
                'reserved': r for r in [
                    {'True': True, 'False': False}.get(data.get('reserved'))
                ] if r is not None
            })

        return merge({'id': session_id}, data) if data else None

    def view_models(self, session_ids=None):
        def get_data():
            data = []
            with self.redis.pipeline() as pipe:
                pipe.watch(SESSION_SET_KEY)
                ids = session_ids
                ids = ids or list(pipe.smembers(SESSION_SET_KEY))
                for session_id in ids:
                    data.append(self.view_model(session_id))
                pipe.execute()
            return data
        return retry(get_data, exception_type=WatchError, raises=False) or []


session_view = SessionAPI.as_view('session_api')
app.add_url_rule('/sessions/', defaults={'session_id': None},
                 view_func=session_view, methods=['GET',])
app.add_url_rule('/sessions/', view_func=session_view, methods=['POST',])
app.add_url_rule('/sessions/<session_id>', view_func=session_view,
                 methods=['GET', 'PUT', 'DELETE'])

app.wsgi_app = ProxyFix(app.wsgi_app)
