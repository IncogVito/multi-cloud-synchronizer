from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DEBUG: bool = False
    LOG_LEVEL: str = "info"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()
