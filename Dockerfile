FROM python:3.11-slim

WORKDIR /app

# Install runtime dependencies first for better layer caching.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# kb-rag-parser listens on 8001 per M1-CONTRACTS.md §0 port assignment.
EXPOSE 8001

# No outbound network access is required or expected at runtime
# (requirement doc §4.2): the container's network policy should only allow
# inbound traffic from kb-rag-server.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
