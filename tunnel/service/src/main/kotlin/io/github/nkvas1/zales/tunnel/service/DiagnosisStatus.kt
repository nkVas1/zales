// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.os.Parcel
import android.os.Parcelable
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.diagnostics.Diagnosis
import io.github.nkvas1.zales.tunnel.diagnostics.ProbeStep
import io.github.nkvas1.zales.tunnel.diagnostics.StepOutcome
import io.github.nkvas1.zales.tunnel.diagnostics.StepResult

/**
 * A [Diagnosis] flattened for the trip out of the `:tunnel` process.
 *
 * Three parallel arrays rather than a list of parcelled objects: the shape is
 * fixed by [ProbeStep], so there is nothing to negotiate, and what crosses the
 * boundary stays small enough to send on every rung without thinking about it.
 *
 * The report travels as the finished English text. It is built on the far side
 * of this boundary, where the key is, and arrives already scrubbed — the UI
 * process never sees a key and so can never leak one.
 */
public data class DiagnosisStatus(
    val marks: IntArray,
    val millis: LongArray,
    val notes: Array<String?>,
    val verdictCode: Int,
    val detail: String,
    val finished: Boolean,
    val report: String,
) : Parcelable {

    /** The flattened form of [StepOutcome]: everything the screen draws. */
    public enum class Mark { PENDING, RUNNING, OK, FAILED, SKIPPED }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeIntArray(marks)
        dest.writeLongArray(millis)
        dest.writeStringArray(notes)
        dest.writeInt(verdictCode)
        dest.writeString(detail)
        dest.writeInt(if (finished) 1 else 0)
        dest.writeString(report)
    }

    public fun toDiagnosis(): Diagnosis = Diagnosis(
        steps = ProbeStep.entries.mapIndexed { index, step ->
            val note = notes.getOrNull(index)
            val took = millis.getOrNull(index) ?: 0
            StepResult(
                step = step,
                outcome = when (Mark.entries[marks.getOrNull(index) ?: 0]) {
                    Mark.PENDING -> StepOutcome.Pending
                    Mark.RUNNING -> StepOutcome.Running
                    Mark.OK -> StepOutcome.Ok(took, note)
                    Mark.SKIPPED -> StepOutcome.Skipped
                    Mark.FAILED -> StepOutcome.Failed(
                        code = verdictCode.takeIf { it >= 0 }?.let { FailureCode.entries[it] } ?: FailureCode.INT_01,
                        detail = note.orEmpty(),
                    )
                },
            )
        },
        verdict = verdictCode.takeIf { it >= 0 }?.let { FailureCode.entries[it] },
        detail = detail,
        finished = finished,
        report = report,
    )

    // Arrays make the generated equals() reference-based, which would make two
    // identical updates look different and redraw the screen for nothing.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? DiagnosisStatus ?: return false
        return marks.contentEquals(that.marks) &&
            millis.contentEquals(that.millis) &&
            notes.contentEquals(that.notes) &&
            verdictCode == that.verdictCode &&
            detail == that.detail &&
            finished == that.finished &&
            report == that.report
    }

    override fun hashCode(): Int {
        var result = marks.contentHashCode()
        result = HASH_STEP * result + millis.contentHashCode()
        result = HASH_STEP * result + notes.contentHashCode()
        result = HASH_STEP * result + verdictCode
        result = HASH_STEP * result + detail.hashCode()
        result = HASH_STEP * result + finished.hashCode()
        result = HASH_STEP * result + report.hashCode()
        return result
    }

    public companion object {
        private const val ABSENT = -1
        private const val HASH_STEP = 31

        @JvmField
        public val CREATOR: Parcelable.Creator<DiagnosisStatus> = object : Parcelable.Creator<DiagnosisStatus> {
            override fun createFromParcel(source: Parcel): DiagnosisStatus {
                val size = ProbeStep.entries.size
                val marks = IntArray(size)
                val millis = LongArray(size)
                source.readIntArray(marks)
                source.readLongArray(millis)
                val notes = source.createStringArray() ?: arrayOfNulls(size)
                return DiagnosisStatus(
                    marks = marks,
                    millis = millis,
                    notes = notes,
                    verdictCode = source.readInt(),
                    detail = source.readString().orEmpty(),
                    finished = source.readInt() == 1,
                    report = source.readString().orEmpty(),
                )
            }

            override fun newArray(size: Int): Array<DiagnosisStatus?> = arrayOfNulls(size)
        }

        public fun of(diagnosis: Diagnosis): DiagnosisStatus = DiagnosisStatus(
            marks = diagnosis.steps.map { it.outcome.mark.ordinal }.toIntArray(),
            millis = diagnosis.steps.map { (it.outcome as? StepOutcome.Ok)?.millis ?: 0 }.toLongArray(),
            notes = diagnosis.steps.map { it.outcome.note }.toTypedArray(),
            verdictCode = diagnosis.verdict?.ordinal ?: ABSENT,
            detail = diagnosis.detail,
            finished = diagnosis.finished,
            report = diagnosis.report,
        )

        private val StepOutcome.mark: Mark
            get() = when (this) {
                StepOutcome.Pending -> Mark.PENDING
                StepOutcome.Running -> Mark.RUNNING
                is StepOutcome.Ok -> Mark.OK
                is StepOutcome.Failed -> Mark.FAILED
                StepOutcome.Skipped -> Mark.SKIPPED
            }

        private val StepOutcome.note: String?
            get() = when (this) {
                is StepOutcome.Ok -> note
                is StepOutcome.Failed -> detail
                else -> null
            }
    }
}
