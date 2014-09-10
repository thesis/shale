from itertools import chain

__all__ = ['permit', 'merge']

def permit(d, permitted_keys):
    return dict((k,v) for k, v in d.iteritems() if k in permitted_keys)

def merge(d, defaults):
    return dict(chain(defaults.iteritems(), d.iteritems()))

def retry(func, exception_type=Exception, times=3, raises=False):
    exception = None
    for i in xrange(times):
        try:
            return func()
        except exception_type, e:
            exception = e
            continue
        else:
            break
    else:
        if raises:
            raise exception
