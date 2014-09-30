python
------

Get a client

.. code:: python

    >>> import shale
    >>> client = shale.default_client
    >>> # or, use a custom client
    >>> client = shale.Client('http://my-shale-host:5000')

List all running webdrivers.

.. code:: python

    >>> client.running_browsers()
    ({u'browser_name': u'phantomjs',
      u'node': u'http://localhost:5555/wd/hub',
      u'id': u'31027408-3e45-4d27-9770-ba1a26953dfc',
      u'reserved': True,
      u'tags': [u'target', u'linux']},)

Reserve a webdriver.

.. code:: python

    >>> browser = client.reserve_browser('31027408-3e45-4d27-9770-ba1a26953dfc')
    <shale.client.ClientResumableRemote at 0x1d92fd0>

... and release it.

.. code:: python

    >>> browser.release()

There's a handy context manager for reserving & releasing webdrivers.

.. code:: python

    >>> with client.browser(browser_name='chrome', tags=['logged-in']) as browser:
    ...     # do yo thang
    ...     pass
    ...
    >>> with client.browser(session_id='123...') as browser:
    ...     # do yo thang with a particular browser session
    ...     pass

You can also force create a new remote webdriver.

.. code:: python

    >>> browser = client.create_browser()
