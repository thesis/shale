from setuptools import setup
import pkg_resources
import re


def read(path):
    with open(pkg_resources.resource_filename(__name__, path)) as f:
        return f.read()


def long_description():
    return re.split('\n\.\. pypi [^\n]*\n', read('README.rst'), 1)[1]


setup(
    name='shale',
    version='0.1',
    author='Matt Luongo',
    author_email='mhluongo@gmail.com',
    packages=['shale'],
    url='https://github.com/cardforcoin/shale',
    license='MIT',
    description='Flask-backed REST API to manage Selenium sessions',
    long_description=long_description(),
    classifiers=[
        'Intended Audience :: Developers',
        'License :: OSI Approved :: MIT License',
        'Programming Language :: Python',
        'Topic :: Software Development :: Libraries :: Python Modules',
        'Programming Language :: Python :: 2.7',
        'Programming Language :: Python :: 3.3',
        'Programming Language :: Python :: 3.4',
    ],
    install_requires=read('requirements.txt').split('\n'),
    tests_require=read('test_requirements.txt').split('\n'),
)
