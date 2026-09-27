package com.clinicit.prediction.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.prediction.application.WaitTimePredictionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WaitEstimateController {

    private final WaitTimePredictionService predictions;

    public WaitEstimateController(WaitTimePredictionService predictions) {
        this.predictions = predictions;
    }

    /** Approximate waits for one doctor's waiting patients today. Doctors: their own queue only. */
    @GetMapping("/api/v1/queues/today/wait-estimates")
    @AnyStaff
    public WaitEstimatesResponse waitEstimates(Actor actor, @RequestParam(required = false) UUID doctorId) {
        return predictions.forDoctorToday(actor, doctorId);
    }
}
