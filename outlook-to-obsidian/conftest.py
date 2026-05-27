"""Make the project root importable as ``src`` regardless of pytest invocation."""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
