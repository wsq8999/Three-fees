from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Header


class ServiceUnauthorizedError(RuntimeError):
    pass


class ServiceAuthenticator:
    def __init__(self, service_token: str | None) -> None:
        self._service_token = service_token

    def validate_configuration(self) -> None:
        if self._service_token is None or len(self._service_token) < 16:
            raise RuntimeError("AI_SERVICE_TOKEN must be configured with at least 16 characters")

    def require(self, authorization: Annotated[str | None, Header()] = None) -> None:
        expected = self._service_token
        prefix = "Bearer "
        if (
            expected is None
            or authorization is None
            or not authorization.startswith(prefix)
            or not secrets.compare_digest(authorization[len(prefix) :], expected)
        ):
            raise ServiceUnauthorizedError
