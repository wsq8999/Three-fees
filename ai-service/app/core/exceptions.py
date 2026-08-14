from __future__ import annotations

class AppError(Exception):
    def __init__(self, *, status: int, code: str, title: str, detail: str) -> None:
        super().__init__(detail)
        self.status = status
        self.code = code
        self.title = title
        self.detail = detail


class ResourceNotFoundError(AppError):
    def __init__(self, detail: str = "请求的资源不存在") -> None:
        super().__init__(status=404, code="resource_not_found", title="资源不存在", detail=detail)


class ConflictError(AppError):
    def __init__(self, detail: str) -> None:
        super().__init__(status=409, code="resource_conflict", title="资源状态冲突", detail=detail)
