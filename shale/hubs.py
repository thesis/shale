class HubPool(object):
    """
    Basic interface for choosing and managing Selenium hubs per session.
    Implementing this allows dynamic hub domains- eg, by retrieving them from
    a cloud provider's API.

    There are also stubs for scaling up and down, though they aren't used in
    the library yet.
    """
    can_add_hub = False
    can_remove_hub = False

    def get_hub(self, **kwargs):
        """
        Returns a hub domain name and port, eg "mydomain:4444", based on the
        same mapping provided to POST /sessions/. Eg, 'browser_name', 'tags',
        etc.
        """
        raise NotImplementedError()

    def add_hub(self, **kwargs):
        """
        Create a new hub, if possible. Uses the same mapping as POST /sessions/.
        """
        raise NotImplementedError()

    def remove_hub(self, hub):
        """
        Destroy the named domain:port hub.
        """
        raise NotImplementedError()


class DefaultHubPool(HubPool):
    """
    Simply returns localhost when asksed for a hub.
    """
    def get_hub(self, **kwargs):
        return 'http://localhost:4444/wd/hub'
