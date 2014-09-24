class NodePool(object):
    """
    Basic interface for choosing and managing Selenium nodes per session.
    Implementing this allows dynamic node domains- eg, by retrieving them from
    a cloud provider's API.

    There are also stubs for scaling up and down, though they aren't used in
    the library yet.
    """
    can_add_node = False
    can_remove_node = False

    def get_node(self, **kwargs):
        """
        Returns a hub domain name and port, eg "mydomain:4444", based on the
        same mapping provided to POST /sessions/. Eg, 'browser_name', 'tags',
        etc.
        """
        raise NotImplementedError()

    def add_node(self, **kwargs):
        """
        Create a new hub, if possible. Uses the same mapping as POST /sessions/.
        """
        raise NotImplementedError()

    def remove_node(self, hub):
        """
        Destroy the named domain:port hub.
        """
        raise NotImplementedError()


class DefaultNodePool(NodePool):
    """
    Simply returns localhost when asksed for a hub.
    """
    def get_node(self, **kwargs):
        return 'http://localhost:5555/wd/hub'
