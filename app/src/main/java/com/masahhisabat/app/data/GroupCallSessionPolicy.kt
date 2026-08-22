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

    fun create(host: String, initialPeer: String): Session =
        Session(host = host, participants = listOf(host, initialPeer).distinct())

    fun canInvite(session: Session, candidate: String, isOnline: Boolean): Boolean =
        isOnline && candidate.isNotBlank() && candidate !in session.participants && !session.isFull

    fun invite(session: Session, candidate: String, isOnline: Boolean): Session =
        if (canInvite(session, candidate, isOnline)) {
            session.copy(participants = session.participants + candidate)
        } else {
            session
        }

    /** معرف اتصال ثابت لكل زوج داخل الغرفة حتى لا تختلط إشارات الأزواج المتعددة. */
    fun pairCallId(roomId: String, first: String, second: String): String =
        "$roomId-${listOf(first, second).sorted().joinToString("-")}"

    /** طرف واحد فقط ينشئ العرض لكل زوج، وفق ترتيب ثابت يمنع سباق offer/offer. */
    fun shouldCreateOffer(currentUser: String, peerUser: String): Boolean =
        currentUser.compareTo(peerUser, ignoreCase = true) < 0
}
