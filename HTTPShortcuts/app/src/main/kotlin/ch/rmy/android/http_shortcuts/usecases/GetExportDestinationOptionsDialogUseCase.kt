package ch.rmy.android.http_shortcuts.usecases

import ch.rmy.android.framework.viewmodel.viewstate.DialogState
import ch.rmy.android.http_shortcuts.R
import javax.inject.Inject

class GetExportDestinationOptionsDialogUseCase
@Inject
constructor() {

    operator fun invoke(
        onExportToFileOptionSelected: () -> Unit,
        onExportViaSharingOptionSelected: () -> Unit,
    ): DialogState =
        DialogState.create {
            title(R.string.title_export)
                .item(R.string.button_export_to_general) {
                    onExportToFileOptionSelected()
                }
                .item(R.string.button_export_send_to) {
                    onExportViaSharingOptionSelected()
                }
                .build()
        }
}
