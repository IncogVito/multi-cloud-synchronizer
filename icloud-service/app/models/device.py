from typing import Optional
from pydantic import BaseModel


class DeviceInfo(BaseModel):
    id: str
    name: str
    model: str
    battery_level: Optional[float] = None
    location: Optional[dict] = None
