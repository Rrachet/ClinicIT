"""The FastAPI service: schema, errors, fallback and model versions."""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from clinicit_ml.api import MAX_INSTANCES, create_app

REQUEST = json.loads(Path("contracts/predict_request_v1.json").read_text())["request"]
INSTANCE = REQUEST["instances"][0]


@pytest.fixture(scope="module")
def client(trained):
    model, _ = trained
    return TestClient(create_app(model))


@pytest.fixture(scope="module")
def no_model_client():
    return TestClient(create_app(None))


def post(client, body):
    return client.post("/predict/wait-time", json=body)


def test_the_request_spring_boot_sends_is_accepted(client, trained):
    model, _ = trained
    response = post(client, REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "1"
    assert body["modelVersion"] == model.version
    assert len(body["predictions"]) == 2
    first, second = body["predictions"]
    assert set(first) == {"estimatedWaitMinutes", "lowerBoundMinutes", "upperBoundMinutes", "source", "reason"}
    assert first["source"] == "MODEL" and first["reason"] is None
    assert 0 <= first["lowerBoundMinutes"] <= first["estimatedWaitMinutes"] <= first["upperBoundMinutes"]
    # No history for the second row: the model declines and says why.
    assert second["source"] == "BASELINE" and second["reason"] == "INSUFFICIENT_HISTORY"


def test_predictions_come_back_in_request_order(client):
    rows = [dict(INSTANCE, patientsAhead=n, queueLength=n + 1) for n in (0, 6, 2)]
    predictions = post(client, {"schemaVersion": "1", "instances": rows}).json()["predictions"]
    estimates = [p["estimatedWaitMinutes"] for p in predictions]
    assert estimates[1] > estimates[2] > estimates[0]


def test_without_a_model_every_row_is_the_labelled_baseline(no_model_client):
    body = post(no_model_client, REQUEST).json()
    assert body["modelVersion"] == "baseline-v1"
    assert {p["source"] for p in body["predictions"]} == {"BASELINE"}
    assert {p["reason"] for p in body["predictions"]} == {"MODEL_NOT_LOADED"}
    assert body["predictions"][0]["estimatedWaitMinutes"] == 23  # 2 ahead × 11.5 min


def test_health_reports_the_model_version(client, no_model_client, trained):
    model, _ = trained
    assert client.get("/health").json() == {"status": "UP", "modelLoaded": True, "modelVersion": model.version}
    assert no_model_client.get("/health").json()["modelLoaded"] is False


class TestErrors:
    def test_unsupported_schema_version(self, client):
        response = post(client, dict(REQUEST, schemaVersion="2"))
        assert response.status_code == 400
        assert response.json()["code"] == "UNSUPPORTED_SCHEMA_VERSION"

    def test_malformed_json(self, client):
        response = client.post("/predict/wait-time", content=b"{not json", headers={"Content-Type": "application/json"})
        assert response.status_code == 400
        assert response.json()["code"] == "MALFORMED_JSON"

    @pytest.mark.parametrize("change, field", [
        ({"patientsAhead": -1}, "instances.0.patientsAhead"),
        ({"dayOfWeek": 8}, "instances.0.dayOfWeek"),
        ({"minuteOfDay": 1440}, "instances.0.minuteOfDay"),
        ({"doctorBusy": 2}, "instances.0.doctorBusy"),
        ({"recentConsultationMinutes": -3}, "instances.0.recentConsultationMinutes"),
        ({"patientsAhead": "three"}, "instances.0.patientsAhead"),
    ])
    def test_out_of_range_values_name_the_field(self, client, change, field):
        response = post(client, {"schemaVersion": "1", "instances": [dict(INSTANCE, **change)]})
        assert response.status_code == 422
        body = response.json()
        assert body["code"] == "INVALID_REQUEST"
        assert field in [e["field"] for e in body["errors"]]

    def test_a_missing_field(self, client):
        row = {k: v for k, v in INSTANCE.items() if k != "queueLength"}
        body = post(client, {"schemaVersion": "1", "instances": [row]}).json()
        assert "instances.0.queueLength" in [e["field"] for e in body["errors"]]

    def test_unknown_fields_are_rejected_so_patient_data_cannot_slip_in(self, client):
        response = post(client, {"schemaVersion": "1", "instances": [dict(INSTANCE, patientName="Asha Rao")]})
        assert response.status_code == 422
        assert "Asha" not in response.text

    def test_inconsistent_queue_position(self, client):
        response = post(client, {"schemaVersion": "1", "instances": [dict(INSTANCE, patientsAhead=3, queueLength=3)]})
        assert response.status_code == 422
        assert "patientsAhead must be less than queueLength" in response.text

    def test_empty_and_oversized_batches(self, client):
        assert post(client, {"schemaVersion": "1", "instances": []}).status_code == 422
        too_many = {"schemaVersion": "1", "instances": [INSTANCE] * (MAX_INSTANCES + 1)}
        assert post(client, too_many).status_code == 422

    def test_errors_do_not_echo_submitted_values(self, client):
        response = post(client, {"schemaVersion": "1", "instances": [dict(INSTANCE, historicalWaitMinutes=-987654)]})
        assert response.status_code == 422
        assert "987654" not in response.text
