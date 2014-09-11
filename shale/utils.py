from itertools import chain

__all__ = ['permit', 'merge']

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
