package ch.rmy.android.http_shortcuts.matrix

import android.content.Context
import ch.rmy.android.http_shortcuts.data.models.ShortcutModel
import ch.rmy.android.http_shortcuts.http.FileUploadManager
import ch.rmy.android.http_shortcuts.http.ResponseFileStorage
import ch.rmy.android.http_shortcuts.http.ShortcutResponse
import ch.rmy.android.http_shortcuts.variables.VariableManager
import io.reactivex.Single
import java.lang.Exception
import javax.inject.Inject

class MatrixShortcutClient @Inject constructor() {
    fun executeShortcut(
        context: Context,
        shortcut: ShortcutModel,
        variableManager: VariableManager,
        responseFileStorage: ResponseFileStorage,
        fileUploadManager: FileUploadManager? = null,
    ): Single<ShortcutResponse> = Single.error(Exception("Matrix not implemented in this flavor"))
}
