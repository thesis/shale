class ShaleException(Exception):
    pass

class TimeoutException(ShaleException):
    def __init__(self, message):
        self.message = message
