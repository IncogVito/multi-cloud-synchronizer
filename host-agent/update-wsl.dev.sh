sudo cp host-agent/agent.py /opt/cloudsync-host-agent/agent.py
sudo cp host-agent/handlers/drive.py /opt/cloudsync-host-agent/handlers/drive.py
sudo cp host-agent/handlers/iphone.py /opt/cloudsync-host-agent/handlers/iphone.py

sudo LOG_LEVEL=DEBUG python3 /opt/cloudsync-host-agent/agent.py