class ShaleException(Exception):
    def __init__(self, message):
        self.message = message

    def __repr__(self):
        return "{}(message={})".format(type(self).__name__, self.message)


class TimeoutException(ShaleException):
    pass


class UnresolvedHost(ShaleException):
    def __init__(self, host):
        self.message = "Unresolved host: {}".format(host)


class NodeNotFound(ShaleException):
    def __init__(self, node_url):
        self.message = "Provided node URL {} not responding.".format(node_url)
