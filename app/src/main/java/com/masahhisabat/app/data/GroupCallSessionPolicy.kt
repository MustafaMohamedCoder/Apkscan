package com.masahhisabat.app.data

/**
 * سياسة جلسة مكالمة جماعية محلية بنمط شبكة نظير-إلى-نظير صغيرة.
 * الحد أربعة مشاركين مقصود لحماية البطارية والذاكرة على الأجهزة المستهدفة.
 */
object GroupCallSessionPolicy {
    const val MAX_PARTICIPANTS = 4

    data class Session(
        val host: String,
        val participants: List<String>
    ) {
        val isFull: Boolean get() = participants.size >= MAX_PARTICIPANTS
    }

    fun create(host: String, initialPeer: String): Session {
        val participants = listOf(host, initialPeer)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        return Session(host = host.trim(), participants = participants)
    }

    fun canInvite(session: Session, candidate: String, isOnline: Boolean): Boolean {
        val normalizedCandidate = candidate.trim()
        return isOnline && normalizedCandidate.isNotBlank() &&
            session.participants.none { it.equals(normalizedCandidate, ignoreCase = true) } &&
            !session.isFull
    }

    fun invite(session: Session, candidate: String, isOnline: Boolean): Session {
        val normalizedCandidate = candidate.trim()
        return if (canInvite(session, normalizedCandidate, isOnline)) {
            session.copy(participants = session.participants + normalizedCandidate)
        } else {
            session
        }
    }

    /** معرف اتصال ثابت لكل زوج داخل الغرفة حتى لا تختلط إشارات الأزواج المتعددة. */
    fun pairCallId(roomId: String, first: String, second: String): String =
        "$roomId-${listOf(first, second).sorted().joinToString("-")}"

    /** طرف واحد فقط ينشئ العرض لكل زوج، وفق ترتيب ثابت يمنع سباق offer/offer. */
    fun shouldCreateOffer(currentUser: String, peerUser: String): Boolean =
        currentUser.compareTo(peerUser, ignoreCase = true) < 0
}
