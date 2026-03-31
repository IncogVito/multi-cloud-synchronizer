from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import health, auth, photos, devices
from app.exceptions import register_exception_handlers

app = FastAPI(
    title="iCloud Service",
    description="iCloud integration microservice for Cloud Synchronizer",
    version="1.0.0",
)

# CORS - allow all origins for dev
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register exception handlers
register_exception_handlers(app)

# Include routers
app.include_router(health.router)
app.include_router(auth.router)
app.include_router(photos.router)
app.include_router(devices.router)
