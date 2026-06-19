import sys
from pathlib import Path

# host-agent root on path so `handlers.*` and `models` import like the daemon does.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
