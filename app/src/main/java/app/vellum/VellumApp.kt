package app.vellum

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class VellumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
