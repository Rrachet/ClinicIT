# ClinicIT wait-time service. Build: docker build -t clinicit-ml ml
# The model is trained from the committed synthetic dataset while building (about 10 s,
# deterministic), so the image never depends on an uncommitted model file.

FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY clinicit_ml ./clinicit_ml
COPY data ./data
RUN python -m clinicit_ml.train --data data/synthetic_wait_times.csv.gz --out models/current
RUN useradd --system --uid 10001 --no-create-home clinicit
USER clinicit
ENV CLINICIT_MODEL_DIR=/app/models/current
EXPOSE 8000
CMD ["uvicorn", "clinicit_ml.api:create_default_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
