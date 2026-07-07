package top.azek431.hzzs

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.btnDevelopmentPlan).setOnClickListener {
            showDevelopmentPlan()
        }
    }

    private fun showDevelopmentPlan() {
        val planSteps = arrayOf(
            getString(R.string.plan_step_1),
            getString(R.string.plan_step_2),
            getString(R.string.plan_step_3),
            getString(R.string.plan_step_4),
            getString(R.string.plan_step_5),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title)
            .setItems(planSteps) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.dialog_btn_close) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}
