package ru.kardiomonitor.features.clinic.domain

/** Идемпотентный отчёт телефона о состоянии запроса непрерывного пульса. */
data class LiveHeartRateRequestStatusReport(
    val status: PhoneLiveHeartRateRequest.Status,
    val failureReason: PhoneLiveHeartRateRequest.FailureReason? = null,
) {
    init {
        require(status != PhoneLiveHeartRateRequest.Status.WaitingForPhone) {
            "телефон не может вернуть запрос в ожидание"
        }
        require((status == PhoneLiveHeartRateRequest.Status.Failed) == (failureReason != null)) {
            "причина обязательна только для состояния failed"
        }
    }
}
