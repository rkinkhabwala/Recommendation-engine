FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml .python-version ./
RUN uv sync --no-install-project
COPY recsys_ml recsys_ml
COPY tests tests
COPY scripts scripts
RUN uv sync
ENTRYPOINT ["uv", "run"]
