from __future__ import absolute_import

try:
    from urllib2 import URLError
except:
    from urllib.error import URLError

import os
import json
from decorator import decorator
from functools import partial
import atexit
import threading
import multiprocessing
import multiprocessing.pool

from redis import ConnectionPool, Redis, WatchError, RedisError
from selenium.webdriver.common.desired_capabilities import DesiredCapabilities
from selenium.common.exceptions import WebDriverException

from flask import request, Response, Flask
from flask.views import MethodView
from werkzeug.contrib.fixers import ProxyFix
from werkzeug.exceptions import NotFound

from .webdriver import ResumableRemote
from .nodes import DefaultNodePool
from .exceptions import TimeoutException
from .utils import permit, merge, retry, str_bool, with_timeout

app = Flask(__name__)

__all__ = ['app']

app.debug = True
app.config.update(
    DEBUG=True,
    NODE_POOL=DefaultNodePool(),
    REDIS_HOST='localhost',
    REDIS_PORT=6379,
    REDIS_DB=0,
    REFRESH_TIME=5, # seconds
)

if 'SHALE_SETTINGS' in os.environ:
    app.config.from_envvar('SHALE_SETTINGS')

REDIS_KEY_PREFIX = '_shale'
SESSION_SET_KEY = '{}_session_set'.format(REDIS_KEY_PREFIX)
SESSION_KEY_TEMPLATE = REDIS_KEY_PREFIX + '_session_{}'
SESSION_TAGS_KEY_TEMPLATE = REDIS_KEY_PREFIX + '_session_{}_tags'

redis_pool = None

thread_pool = None

process_pool = None

def connect_to_redis():
    global redis_pool
    redis_pool = ConnectionPool(host=app.config['REDIS_HOST'],
                                port=app.config['REDIS_PORT'],
                                db=app.config['REDIS_DB'])


def returns_json(func):
    def inner(*args, **kwargs):
        status=200
        data = {}
        try:
            data = func(*args, **kwargs)
        except NotFound:
            status = 404
        except multiprocessing.TimeoutError:
            status = 500
            data = {'error':"Process timed out."}
        except TimeoutException as e:
            status = 500
            data = {'error':e.message}
        return Response(json.dumps(data), content_type="application/json",
                        status=status)
    return inner


class RedisView(object):
    def dispatch_request(self, *args, **kwargs):
        self.redis = Redis(connection_pool=redis_pool)
        return super(RedisView, self).dispatch_request(*args, **kwargs)


class SessionAPI(RedisView, MethodView):

    decorators = [returns_json]

    def get(self, session_id):
        if session_id is None:
            return view_models(self.redis)
        # get the specified session or 404
        view_model = view_model(self.redis, session_id)
        if view_model is None:
            raise NotFound()
        return view_model

    def post(self):
        """
        We're using POST here for get-or-create semantics.
        """
        data = json.loads(request.data) if request.data else {}

        force_create = str_bool(request.args.get('force_create', False))
        reserve = str_bool(request.args.get('reserve', False))

        permitted_keys = ['browser_name', 'node', 'tags', 'reserved',
                          'current_url', 'extra_desired_capabilities']
        permitted = permit(
                dict((k, v) for k, v in data.items() if v is not None),
                permitted_keys)

        with self.redis.pipeline() as pipe:
            if force_create:
                session_id = create_session(self.redis, permitted)
            else:
                session_id = get_or_create_session(self.redis, permitted)

            if reserve:
                reserve_session(self.redis, session_id)

            return view_model(self.redis, session_id)

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
            return view_model(self.redis, session_id)


    def delete(self, session_id):
        return delete_session(self.redis, session_id)


@with_timeout(15, "Timed out getting a new webdriver.")
def get_resumable_remote(*args, **kwargs):
    return ResumableRemote(*args, **kwargs)


@with_timeout(10, "Timed out pinging a webdriver.")
def ping_remote_for_url(*args, **kwargs):
    wd = ResumableRemote(*args, **kwargs)
    return wd.current_url


def create_session(redis, requirements):
    defaults = {
        'browser_name': 'firefox',
        'node': 'http://localhost:5555/wd/hub',
        'tags': [],
        'reserved': False,
        'extra_desired_capabilities':{}
    }
    with redis.pipeline() as pipe:
        # if node was set to None, choose one
        settings = dict(requirements)
        settings['node'] = settings.get('node') or \
                app.config['NODE_POOL'].get_node(**requirements)
        settings = merge(settings, defaults)
        settings.setdefault('browser_name', 'phantomjs')

        cap = {
            'chrome': DesiredCapabilities.CHROME,
            'firefox': DesiredCapabilities.FIREFOX,
            'phantomjs': DesiredCapabilities.PHANTOMJS,
        }.get(settings['browser_name'])
        cap = merge(cap, settings.get('extra_desired_capabilities', {}))

        async_wd = process_pool.apply_async(
                get_resumable_remote, [],
                dict(command_executor=settings['node'], desired_capabilities=cap))
        wd = async_wd.get(20)

        settings['node'] = wd.command_executor._url
        if 'current_url' in settings:
            # we do this in JS to prevent a blocking call
            wd.execute_script(
                'window.location = "{}";'.format(settings['current_url']))

        session_id = wd.session_id
        pipe.sadd(SESSION_SET_KEY, session_id)
        session_key = SESSION_KEY_TEMPLATE.format(wd.session_id)
        permitted = permit(
            settings, ['browser_name', 'node', 'reserved', 'current_url'])
        for key, value in permitted.items():
            pipe.hset(session_key, key, value)
        tags_key = SESSION_TAGS_KEY_TEMPLATE.format(wd.session_id)
        pipe.delete(tags_key)
        for tag in settings['tags']:
            pipe.sadd(tags_key, tag)
        pipe.execute()
        return session_id


def reserve_session(redis, session_id):
    with redis.pipeline() as pipe:
        session_key = SESSION_KEY_TEMPLATE.format(session_id)
        pipe.watch(session_key)
        was_reserved = {'False':False, 'True':True}.get(
                pipe.hget(session_key, 'reserved'), False)
        if was_reserved:
            raise ValueError("Session was already reserved- can't reserve a "
                             "session twice.")
        pipe.hset(session_key, 'reserved', True)
        pipe.execute()


def get_or_create_session(redis, requirements):
    requirements = dict(requirements)
    requirements.setdefault('reserved', False)
    requirements['node'] = resolve_url(requirements['node']) \
            if requirements.get('node', None) else None

    def match(candidate, reqs):
        keys_for_match = ['browser_name', 'reserved', 'current_url']
        cand_tags = set(candidate.get('tags', []))
        req_tags = set(reqs.get('tags', []))
        return (set(permit(candidate, keys_for_match).items()) >=
                    set(permit(reqs, keys_for_match).items())
                and (reqs.get('node') is None or
                     candidate['node'] == reqs['node'])
                and cand_tags >= req_tags)

    with redis.pipeline() as pipe:
        pipe.watch(SESSION_SET_KEY)
        models = view_models(redis)
        candidates = [m for m in models if match(m, requirements)]
        if len(candidates) >= 1:
            session_id = candidates[0]['id']
            return session_id
        return create_session(redis, requirements)


def delete_session(redis, session_id):
    model = view_model(redis, session_id)
    try:
        wd = ResumableRemote(
                session_id=session_id, command_executor=model['node'])
        wd.quit()
    except (WebDriverException, URLError):
        pass
    with redis.pipeline() as pipe:
        pipe.srem(SESSION_SET_KEY, session_id)
        pipe.delete(SESSION_KEY_TEMPLATE.format(session_id))
        pipe.delete(SESSION_TAGS_KEY_TEMPLATE.format(session_id))
        pipe.execute()
    return True


def view_model(redis, session_id, data=None, should_retry=True):
    def get_data():
        ret = None
        with redis.pipeline() as pipe:
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


def view_models(redis, session_ids=None):
    def get_data():
        data = []
        with redis.pipeline() as pipe:
            pipe.watch(SESSION_SET_KEY)
            ids = session_ids
            ids = ids or list(pipe.smembers(SESSION_SET_KEY))
            for session_id in ids:
                data.append(view_model(redis, session_id))
            pipe.execute()
        return data
    return retry(get_data, exception_type=WatchError, raises=False) or []


class SessionRefreshAPI(RedisView, MethodView):

    decorators = [returns_json]

    def post(self, session_id):
        sessions = [view_model(self.redis, session_id)] \
                if session_id else view_models(self.redis)
        refresh_sessions(self.redis, [s['id'] for s in sessions])
        return True


def refresh_session(redis, session_id):
    if redis is None:
        redis = Redis(connection_pool=redis_pool)
    try:
        with redis.pipeline() as pipe:
            pipe.watch(SESSION_SET_KEY)
            session_key = SESSION_KEY_TEMPLATE.format(session_id)
            # TODO handle if the key isn't there
            pipe.watch(session_key)

            node = pipe.hget(session_key, 'node') \
                    or 'http://127.0.0.1:5555/wd/hub'
            async_url = process_pool.apply_async(
                    ping_remote_for_url, [],
                    dict(command_executor=node, session_id=session_id))
            pipe.hset(session_key, 'current_url', async_url.get(15))

            pipe.execute()
    except RedisError:
        # TODO ?? not sure how to handle this
        pass
    except (WebDriverException, URLError, TimeoutException):
        # delete the session
        delete_session(redis, session_id)
        return False
    return True

def refresh_sessions(redis, session_ids=None):
    with redis.pipeline() as pipe:
        pipe.watch(SESSION_SET_KEY)
        session_ids = session_ids or list(pipe.smembers(SESSION_SET_KEY))
        thread_pool.map(partial(refresh_session, None), session_ids)
        pipe.execute()

# routing

session_view = SessionAPI.as_view('session_api')
app.add_url_rule('/sessions/', defaults={'session_id': None},
                 view_func=session_view, methods=['GET',])
app.add_url_rule('/sessions/', view_func=session_view, methods=['POST',])
app.add_url_rule('/sessions/<session_id>', view_func=session_view,
                 methods=['GET', 'PUT', 'DELETE'])
session_refresh_api = SessionRefreshAPI.as_view('session_refresh_api')
app.add_url_rule('/sessions/refresh', view_func=session_refresh_api,
                 defaults={'session_id':None}, methods=['POST'])
app.add_url_rule('/sessions/<session_id>/refresh', view_func=session_refresh_api,
                 methods=['POST'])


@app.before_first_request
def before_first_request():
    global redis_pool
    global thread_pool
    global process_pool
    if redis_pool is None:
        connect_to_redis()
    if process_pool is None:
        process_pool = multiprocessing.Pool()
    if thread_pool is None:
        thread_pool = multiprocessing.pool.ThreadPool()
    # background session-refreshing thread
    def background():
        global redis_pool
        redis = Redis(connection_pool=redis_pool)
        return refresh_sessions(redis)
    background_thread = threading.Timer(
            app.config['REFRESH_TIME'], background, [])
    def background_interrupt():
        background_thread.cancel()
    atexit.register(background_interrupt)
    background_thread.start()

app.wsgi_app = ProxyFix(app.wsgi_app)
