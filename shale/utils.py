import re
import inspect
import socket
import signal
from itertools import chain
from urlparse import urlparse

import decorator

from .exceptions import TimeoutException

__all__ = ['permit', 'merge']


def resolve_url(url):
    """
    Return the URL with the domain replaced by the resolved IP address.
    """
    url_parts = urlparse(url)
    domain_or_ip = url_parts.netloc

    ip = (domain_or_ip if re.match(r'(?:\d+\.){3}\d+', domain_or_ip)
          else (socket.gethostbyname_ex(domain_or_ip)[-1][0]))
    return url_parts._replace(netloc=ip).geturl()


def all_args(f, args, kwargs):
    return dict(kwargs.items() + zip(inspect.getargspec(f).args, args))


def with_timeout(seconds, message=None):
    """
    A signal-based timeout decorator. Since this uses signals, it works best
    with multiprocessing- eg by wrapping a function for a `Process` target.
    Using it with threading is not a good idea.
    """
    def exit(*args):
        raise TimeoutException(message=message)

    @decorator.decorator
    def decorate(f, *args, **kwargs):
        a = all_args(f, args, kwargs)
        signal.signal(signal.SIGALRM, exit)
        signal.alarm(seconds)
        return f(*args, **kwargs)

    return decorate


def permit(d, permitted_keys):
    return dict((k,v) for k, v in d.items() if k in permitted_keys)


def merge(d, defaults):
    return dict(chain(defaults.items(), d.items()))


def retry(func, exception_type=Exception, times=3, raises=False):
    exception = None
    for _ in range(times):
        try:
            return func()
        except exception_type as e:
            exception = e
            continue
    else:
        if raises:
            raise exception


def str_bool(s):
    ret = {'True':True, 'False':False}.get(s, None)
    if ret is None:
        return bool(s)
    return bool(ret)
