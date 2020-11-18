package site.leos.apps.lespas.sync

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import java.io.InputStream
import java.io.OutputStream

class AcquiringDialogFragment: DialogFragment() {
    private var total = -1
    private val acquiringModel: AcquiringViewModel by activityViewModels { AcquiringViewModelFactory(requireActivity().application, arguments?.getParcelableArrayList(URIS)!!) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        total = arguments?.getParcelableArrayList<Uri>(URIS)!!.size
        setStyle(STYLE_NO_TITLE, R.style.Theme_LesPas_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_acquiring_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        current_progress.max = total
        dialog_title_textview.text = getString(R.string.preparing_files, 1, total)

        acquiringModel.getProgress().observe(viewLifecycleOwner, Observer { progress->
            if (progress == total) dismiss()

            dialog_title_textview.text = getString(R.string.preparing_files, progress+1, total)
            filename_textview.text = acquiringModel.getCurrentName()
            current_progress.progress = progress
        })
    }

    override fun onResume() {
        // Set dialog width to a fixed ration of screen width
        val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
        dialog!!.window!!.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        super.onResume()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) activity?.apply {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileName = ""
                var inputStream: InputStream
                var outputStream: OutputStream
                val buf = ByteArray(4096)
                var len: Int

                uris.forEachIndexed { index, uri ->
                    // find out the real name
                    application.contentResolver.query(uri, null, null, null, null)?.apply {
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        fileName = getString(columnIndex)
                        close()
                    }

                    // Update dialog view
                    withContext(Dispatchers.Main) { setProgress(index, fileName) }

                    // Copy the file to our private storage
                    inputStream = application.contentResolver.openInputStream(uri)!!
                    outputStream = application.openFileOutput(fileName, Context.MODE_PRIVATE)
                    len = inputStream.read(buf)
                    while (len > 0) {
                        outputStream.write(buf, 0, len)
                        len = inputStream.read(buf)
                    }
                    inputStream.close()
                    outputStream.close()
                    Log.e("======", "finished copying $fileName")

                    // Try to finish at the end by setting progress to more than 100%
                    withContext(Dispatchers.Main) { setProgress(index + 1, fileName) }
                }
            }
        }

        private fun setProgress(progress: Int, name: String) {
            currentProgress.value = progress
            currentName = name
        }
        fun getProgress(): LiveData<Int> = currentProgress
        fun getCurrentName() = currentName
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AcquiringViewModel(application, uris) as T
    }

    companion object {
        const val URIS = "URIS"

        fun newInstance(uris: ArrayList<Uri>) = AcquiringDialogFragment().apply { arguments = Bundle().apply { putParcelableArrayList(URIS, uris) }}
    }
}