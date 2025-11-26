package io.github.anonymous.pepper_realtime.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.data.PerceptionData
import java.util.Locale

/**
 * Adapter for displaying detected humans in the perception dashboard
 */
class HumanDetectionAdapter : RecyclerView.Adapter<HumanDetectionAdapter.HumanViewHolder>() {

    private val humans = mutableListOf<PerceptionData.HumanInfo>()

    fun updateHumans(newHumans: List<PerceptionData.HumanInfo>?) {
        // Use granular notifications based on size delta
        val oldSize = humans.size
        val newSize = newHumans?.size ?: 0

        if (newHumans.isNullOrEmpty()) {
            // Remove all items if any
            if (oldSize > 0) {
                humans.clear()
                notifyItemRangeRemoved(0, oldSize)
            }
            return
        }

        // Replace backing list
        humans.clear()
        humans.addAll(newHumans)

        when {
            oldSize == 0 -> notifyItemRangeInserted(0, newSize)
            newSize == oldSize -> notifyItemRangeChanged(0, newSize)
            newSize > oldSize -> {
                notifyItemRangeChanged(0, oldSize)
                notifyItemRangeInserted(oldSize, newSize - oldSize)
            }
            else -> { // newSize < oldSize
                notifyItemRangeChanged(0, newSize)
                notifyItemRangeRemoved(newSize, oldSize - newSize)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HumanViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_human_detection, parent, false)
        return HumanViewHolder(view)
    }

    override fun onBindViewHolder(holder: HumanViewHolder, position: Int) {
        holder.bind(humans[position])
    }

    override fun getItemCount(): Int = humans.size

    class HumanViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val facePicture: ImageView = itemView.findViewById(R.id.human_face_picture)
        private val demographics: TextView = itemView.findViewById(R.id.human_demographics)
        private val distance: TextView = itemView.findViewById(R.id.human_distance)
        private val emotion: TextView = itemView.findViewById(R.id.human_emotion)
        private val pleasure: TextView = itemView.findViewById(R.id.human_pleasure)
        private val excitement: TextView = itemView.findViewById(R.id.human_excitement)
        private val smile: TextView = itemView.findViewById(R.id.human_smile)
        private val attention: TextView = itemView.findViewById(R.id.human_attention)
        private val engagement: TextView = itemView.findViewById(R.id.human_engagement)

        // Azure UI Elements
        private val azureHeadPose: TextView = itemView.findViewById(R.id.azure_head_pose)
        private val azureGlasses: TextView = itemView.findViewById(R.id.azure_glasses)
        private val azureMask: TextView = itemView.findViewById(R.id.azure_mask)
        private val azureQuality: TextView = itemView.findViewById(R.id.azure_quality)

        private var lastW = -1
        private var lastH = -1

        fun bind(human: PerceptionData.HumanInfo) {
            // Set face picture with debugging
            val bitmap = human.facePicture
            if (bitmap != null) {
                facePicture.setImageBitmap(bitmap)
                val w = bitmap.width
                val h = bitmap.height
                if (w != lastW || h != lastH) {
                    Log.d(TAG, "🖼️ Displaying face picture for human ${human.id} (${w}x${h})")
                    lastW = w
                    lastH = h
                }
            } else {
                // Use default placeholder
                facePicture.setImageResource(android.R.drawable.ic_menu_gallery)
                Log.d(TAG, "🖼️ Using placeholder for human ${human.id} (no face picture available)")
            }

            // Set demographics
            demographics.text = human.getDemographics()

            // Set distance
            distance.text = human.getDistanceString()

            // Set basic emotion
            emotion.text = human.getBasicEmotionDisplay()

            // Set pleasure state
            pleasure.text = human.getPleasureStateDisplay()

            // Set excitement state
            excitement.text = human.getExcitementStateDisplay()

            // Set smile state
            smile.text = human.getSmileStateDisplay()

            // Set attention state
            attention.text = human.getAttentionLevel()

            // Set engagement state
            engagement.text = human.getEngagementLevel()

            // --- Bind Azure Data ---
            azureHeadPose.text = formatHeadPose(human.azureYawDeg)
            azureGlasses.text = "Glasses: ${human.glassesType}"
            azureMask.text = formatMask(human.isMasked)
            azureQuality.text = "Quality: ${human.imageQuality}"
        }

        private fun formatHeadPose(yawDeg: Double?): String {
            if (yawDeg == null) {
                return "Pose: N/A"
            }
            val direction = when {
                yawDeg < -10.0 -> "Right"
                yawDeg > 10.0 -> "Left"
                else -> "Forward"
            }
            return String.format(Locale.US, "Pose: %s (%.0f°)", direction, yawDeg)
        }

        private fun formatMask(isMasked: Boolean?): String {
            return when (isMasked) {
                null -> "Mask: N/A"
                true -> "Mask: Yes"
                false -> "Mask: No"
            }
        }

        companion object {
            private const val TAG = "HumanAdapter"
        }
    }
}

