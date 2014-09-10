from selenium import webdriver
from selenium.webdriver.remote.switch_to import SwitchTo
from selenium.webdriver.remote.mobile import Mobile
from selenium.webdriver.remote.errorhandler import ErrorHandler
from selenium.webdriver.remote.remote_connection import RemoteConnection


class ResumableRemote(webdriver.Remote):
    def __init__(self, command_executor='http://127.0.0.1:4444/wd/hub',
                 session_id=None, **kwargs):
        #desired_capabilities=None, browser_profile=None, proxy=None, keep_alive=False):
        if session_id is not None:
            self.command_executor = command_executor
            if type(self.command_executor) is bytes or isinstance(self.command_executor, str):
                self.command_executor = RemoteConnection(
                        command_executor, keep_alive=kwargs.get('keep_alive', False))
            self.command_executor._commands['get_session'] = ('GET', '/session/$sessionId')
            self._is_remote = True
            self.start_client()
            self.resume_session(session_id)
            self._switch_to = SwitchTo(self)
            self._mobile = Mobile(self)
            self.error_handler = ErrorHandler()
        else:
            super(ResumableRemote, self).__init__(
                  command_executor=command_executor, **kwargs)

    def resume_session(self, session_id):
        self.session_id = session_id
        response = self.command_executor.execute('get_session', {'sessionId':session_id})
        self.capabilities = response['value']
