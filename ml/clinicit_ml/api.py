"""ClinicIT wait-time prediction service.

    uvicorn clinicit_ml.api:create_default_app --factory --port 8000
    (model from $CLINICIT_MODEL_DIR, default models/current)

POST /predict/wait-time, schema version "1":

    request  {"schemaVersion": "1", "instances": [{"dayOfWeek": 2, "minuteOfDay": 600, ...}, ...]}
    response {"schemaVersion": "1", "modelVersion": "wait-…",
              "predictions": [{"estimatedWaitMinutes": 23.4, "lowerBoundMinutes": 17.0,
                               "upperBoundMinutes": 31.2, "source": "MODEL", "reason": null}, ...]}

One prediction per instance, in order. `source` is BASELINE (with a `reason`) when the model
may not be used for that row, or when no model is loaded. The service predicts an
operational waiting time only: it receives no patient identity or clinical information and
has no way to prioritise or triage anyone (docs/AI.md).
"""

from __future__ import annotations

import logging
import os
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from . import baseline
from .model import WaitTimeModel

SCHEMA_VERSION = "1"
MAX_INSTANCES = 200

log = logging.getLogger("clinicit_ml")


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Instance(_Camel):
    """What ClinicIT knew about one waiting patient's situation at prediction time."""

    day_of_week: int = Field(ge=1, le=7)
    minute_of_day: float = Field(ge=0, lt=1440)
    patients_ahead: int = Field(ge=0, le=500)
    queue_length: int = Field(ge=1, le=500)
    completed_today: int = Field(ge=0, le=1000)
    doctor_busy: int = Field(ge=0, le=1)
    active_patient_minutes: float = Field(ge=0, le=1440)
    calls_last_hour: int = Field(ge=0, le=1000)
    recent_consultation_minutes: float | None = Field(default=None, ge=0, le=1440)
    historical_consultation_minutes: float | None = Field(default=None, ge=0, le=1440)
    historical_consultation_count: int = Field(ge=0)
    historical_wait_minutes: float | None = Field(default=None, ge=0, le=1440)

    @model_validator(mode="after")
    def _consistent(self) -> "Instance":
        if self.patients_ahead >= self.queue_length:
            raise ValueError("patientsAhead must be less than queueLength (the queue includes the patient)")
        if self.doctor_busy == 0 and self.active_patient_minutes > 0:
            raise ValueError("activePatientMinutes must be 0 when doctorBusy is 0")
        return self


class WaitTimeRequest(_Camel):
    schema_version: Literal["1"]
    instances: list[Instance] = Field(min_length=1, max_length=MAX_INSTANCES)


class PredictionOut(_Camel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    estimated_wait_minutes: float
    lower_bound_minutes: float
    upper_bound_minutes: float
    source: Literal["MODEL", "BASELINE"]
    reason: str | None = None


class WaitTimeResponse(_Camel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, protected_namespaces=())

    schema_version: str = SCHEMA_VERSION
    model_version: str
    predictions: list[PredictionOut]


def create_app(model: WaitTimeModel | None) -> FastAPI:
    app = FastAPI(title="ClinicIT wait-time prediction", version=SCHEMA_VERSION,
                  description="Operational estimate of how long a patient waits before being called. Advisory only.")

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError) -> JSONResponse:
        errors = exc.errors()
        if any(e.get("type") == "json_invalid" for e in errors):
            return JSONResponse(status_code=400, content={"code": "MALFORMED_JSON", "message": "Request body is not valid JSON"})
        if any(e.get("loc", ())[-1:] == ("schemaVersion",) and e.get("type") == "literal_error" for e in errors):
            return JSONResponse(status_code=400, content={
                "code": "UNSUPPORTED_SCHEMA_VERSION",
                "message": f"Only schemaVersion \"{SCHEMA_VERSION}\" is supported"})
        # Field locations and messages only; never echo the submitted values back.
        details = [{"field": ".".join(str(p) for p in e.get("loc", ())[1:]), "message": e.get("msg", "")} for e in errors]
        return JSONResponse(status_code=422, content={"code": "INVALID_REQUEST", "message": "Request failed validation",
                                                      "errors": details})

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP", "modelLoaded": model is not None,
                "modelVersion": model.version if model else baseline.VERSION}

    @app.post("/predict/wait-time", response_model=WaitTimeResponse, response_model_by_alias=True)
    def predict(request: WaitTimeRequest) -> WaitTimeResponse:
        rows = [instance.model_dump() for instance in request.instances]
        if model is None:
            predictions = []
            for row in rows:
                point, lower, upper = baseline.estimate(row)
                predictions.append(PredictionOut(estimated_wait_minutes=point, lower_bound_minutes=lower,
                                                 upper_bound_minutes=upper, source="BASELINE", reason="MODEL_NOT_LOADED"))
            return WaitTimeResponse(model_version=baseline.VERSION, predictions=predictions)

        return WaitTimeResponse(model_version=model.version, predictions=[
            PredictionOut(estimated_wait_minutes=round(float(p.estimated_wait_minutes), 1),
                          lower_bound_minutes=round(float(p.lower_bound_minutes), 1),
                          upper_bound_minutes=round(float(p.upper_bound_minutes), 1),
                          source=p.source, reason=p.reason)
            for p in model.predict(rows)])

    return app


def create_default_app() -> FastAPI:
    return create_app(load_model(os.environ.get("CLINICIT_MODEL_DIR", "models/current")))


def load_model(directory: str) -> WaitTimeModel | None:
    try:
        model = WaitTimeModel.load(directory)
        log.warning("Loaded wait-time model %s", model.version)
        return model
    except FileNotFoundError:
        log.warning("No wait-time model in %s: every prediction will be the baseline", directory)
        return None
