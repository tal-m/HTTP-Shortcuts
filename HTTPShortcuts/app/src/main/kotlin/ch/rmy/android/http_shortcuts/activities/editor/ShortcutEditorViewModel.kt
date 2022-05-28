package ch.rmy.android.http_shortcuts.activities.editor

import android.app.Activity
import android.app.Application
import ch.rmy.android.framework.extensions.attachTo
import ch.rmy.android.framework.extensions.color
import ch.rmy.android.framework.extensions.context
import ch.rmy.android.framework.extensions.runIfNotNull
import ch.rmy.android.framework.extensions.subscribeOptional
import ch.rmy.android.framework.extensions.toLocalizable
import ch.rmy.android.framework.utils.UUIDUtils.newUUID
import ch.rmy.android.framework.utils.localization.Localizable
import ch.rmy.android.framework.utils.localization.QuantityStringLocalizable
import ch.rmy.android.framework.utils.localization.StringResLocalizable
import ch.rmy.android.framework.viewmodel.BaseViewModel
import ch.rmy.android.framework.viewmodel.WithDialog
import ch.rmy.android.framework.viewmodel.viewstate.DialogState
import ch.rmy.android.http_shortcuts.R
import ch.rmy.android.http_shortcuts.activities.ExecuteActivity
import ch.rmy.android.http_shortcuts.activities.editor.advancedsettings.AdvancedSettingsActivity
import ch.rmy.android.http_shortcuts.activities.editor.authentication.AuthenticationActivity
import ch.rmy.android.http_shortcuts.activities.editor.basicsettings.BasicRequestSettingsActivity
import ch.rmy.android.http_shortcuts.activities.editor.body.RequestBodyActivity
import ch.rmy.android.http_shortcuts.activities.editor.executionsettings.ExecutionSettingsActivity
import ch.rmy.android.http_shortcuts.activities.editor.headers.RequestHeadersActivity
import ch.rmy.android.http_shortcuts.activities.editor.response.ResponseActivity
import ch.rmy.android.http_shortcuts.activities.editor.scripting.ScriptingActivity
import ch.rmy.android.http_shortcuts.activities.editor.shortcuts.TriggerShortcutsActivity
import ch.rmy.android.http_shortcuts.activities.editor.usecases.FetchFaviconUseCase
import ch.rmy.android.http_shortcuts.dagger.getApplicationComponent
import ch.rmy.android.http_shortcuts.data.SessionInfoStore
import ch.rmy.android.http_shortcuts.data.domains.categories.CategoryId
import ch.rmy.android.http_shortcuts.data.domains.shortcuts.ShortcutId
import ch.rmy.android.http_shortcuts.data.domains.shortcuts.ShortcutRepository
import ch.rmy.android.http_shortcuts.data.domains.shortcuts.TemporaryShortcutRepository
import ch.rmy.android.http_shortcuts.data.domains.variables.VariableRepository
import ch.rmy.android.http_shortcuts.data.enums.RequestBodyType
import ch.rmy.android.http_shortcuts.data.enums.ShortcutAuthenticationType
import ch.rmy.android.http_shortcuts.data.enums.ShortcutExecutionType
import ch.rmy.android.http_shortcuts.data.maintenance.CleanUpWorker
import ch.rmy.android.http_shortcuts.data.models.ShortcutModel
import ch.rmy.android.http_shortcuts.data.models.ShortcutModel.Companion.TEMPORARY_ID
import ch.rmy.android.http_shortcuts.extensions.type
import ch.rmy.android.http_shortcuts.icons.Icons
import ch.rmy.android.http_shortcuts.icons.ShortcutIcon
import ch.rmy.android.http_shortcuts.scripting.shortcuts.TriggerShortcutManager
import ch.rmy.android.http_shortcuts.usecases.GetBuiltInIconPickerDialogUseCase
import ch.rmy.android.http_shortcuts.usecases.GetIconPickerDialogUseCase
import ch.rmy.android.http_shortcuts.utils.LauncherShortcutManager
import ch.rmy.android.http_shortcuts.utils.Validation.isAcceptableHttpUrl
import ch.rmy.android.http_shortcuts.utils.Validation.isAcceptableUrl
import ch.rmy.android.http_shortcuts.variables.VariablePlaceholderProvider
import ch.rmy.android.http_shortcuts.variables.Variables
import ch.rmy.android.http_shortcuts.widget.WidgetManager
import ch.rmy.curlcommand.CurlCommand
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class ShortcutEditorViewModel(
    application: Application,
) : BaseViewModel<ShortcutEditorViewModel.InitData, ShortcutEditorViewState>(application), WithDialog {

    @Inject
    lateinit var shortcutRepository: ShortcutRepository

    @Inject
    lateinit var temporaryShortcutRepository: TemporaryShortcutRepository

    @Inject
    lateinit var variableRepository: VariableRepository

    @Inject
    lateinit var widgetManager: WidgetManager

    @Inject
    lateinit var fetchFavicon: FetchFaviconUseCase

    @Inject
    lateinit var getIconPickerDialog: GetIconPickerDialogUseCase

    @Inject
    lateinit var getBuiltInIconPickerDialog: GetBuiltInIconPickerDialogUseCase

    @Inject
    lateinit var launcherShortcutManager: LauncherShortcutManager

    @Inject
    lateinit var sessionInfoStore: SessionInfoStore

    @Inject
    lateinit var variablePlaceholderProvider: VariablePlaceholderProvider

    init {
        getApplicationComponent().inject(this)
    }

    private val variablePlaceholderColor by lazy {
        color(context, R.color.variable)
    }

    private var isSaving = false
        set(value) {
            if (field != value) {
                field = value
                updateViewState {
                    copy(isInputDisabled = value)
                }
            }
        }

    private var oldShortcut: ShortcutModel? = null

    private lateinit var shortcut: ShortcutModel

    private val categoryId
        get() = initData.categoryId

    private val shortcutId
        get() = initData.shortcutId

    private val executionType
        get() = initData.executionType

    override var dialogState: DialogState?
        get() = currentViewState?.dialogState
        set(value) {
            updateViewState {
                copy(dialogState = value)
            }
        }

    override fun onInitializationStarted(data: InitData) {
        sessionInfoStore.editingShortcutId = data.shortcutId
        sessionInfoStore.editingShortcutCategoryId = data.categoryId
        when {
            data.recoveryMode -> Completable.complete()
            data.shortcutId == null -> {
                temporaryShortcutRepository.createNewTemporaryShortcut(
                    initialIcon = Icons.getRandomInitialIcon(context),
                    executionType = executionType,
                )
            }
            else -> {
                shortcutRepository.createTemporaryShortcutFromShortcut(data.shortcutId)
            }
        }
            .runIfNotNull(data.curlCommand) {
                andThen(temporaryShortcutRepository.importFromCurl(it))
            }
            .subscribe(
                {
                    observeTemporaryShortcut()
                },
                { error ->
                    finish()
                    handleUnexpectedError(error)
                },
            )
            .attachTo(destroyer)

        variableRepository.getObservableVariables()
            .subscribe { variables ->
                variablePlaceholderProvider.applyVariables(variables)
            }
            .attachTo(destroyer)
    }

    override fun initViewState() = ShortcutEditorViewState(
        toolbarTitle = StringResLocalizable(if (shortcutId != null) R.string.edit_shortcut else R.string.create_shortcut),
        shortcutExecutionType = executionType,
    )

    private fun observeTemporaryShortcut() {
        temporaryShortcutRepository.getObservableTemporaryShortcut()
            .subscribe { shortcut ->
                this.shortcut = shortcut
                if (!isInitialized) {
                    oldShortcut = shortcut
                    finalizeInitialization(silent = true)
                }
                updateViewState {
                    copy(
                        toolbarSubtitle = getToolbarSubtitle(),
                        shortcutExecutionType = shortcut.type,
                        shortcutIcon = shortcut.icon,
                        shortcutName = shortcut.name,
                        shortcutRoom = shortcut.room,
                        shortcutDescription = shortcut.description,
                        isExecutable = canExecute(),
                        hasChanges = hasChanges(),
                        requestBodyButtonEnabled = shortcut.allowsBody(),
                        basicSettingsSubtitle = getBasicSettingsSubtitle(),
                        headersSubtitle = getHeadersSubtitle(),
                        requestBodySubtitle = getRequestBodySubtitle(),
                        requestBodySettingsSubtitle = getRequestBodySubtitle(),
                        authenticationSettingsSubtitle = getAuthenticationSubtitle(),
                        scriptingSubtitle = getScriptingSubtitle(),
                        triggerShortcutsSubtitle = getTriggerShortcutsSubtitle(),
                    )
                }
            }
            .attachTo(destroyer)
    }

    private fun hasChanges() =
        initData.recoveryMode || oldShortcut?.isSameAs(shortcut) == false || initData.curlCommand != null

    private fun canExecute() =
        !shortcut.type.usesUrl ||
            (shortcut.type.requiresHttpUrl && isAcceptableHttpUrl(shortcut.url)) ||
            (!shortcut.type.requiresHttpUrl && isAcceptableUrl(shortcut.url))

    private fun getToolbarSubtitle() =
        when (shortcut.type) {
            ShortcutExecutionType.BROWSER -> StringResLocalizable(R.string.subtitle_editor_toolbar_browser_shortcut)
            ShortcutExecutionType.SCRIPTING -> StringResLocalizable(R.string.subtitle_editor_toolbar_scripting_shortcut)
            ShortcutExecutionType.TRIGGER -> StringResLocalizable(R.string.subtitle_editor_toolbar_trigger_shortcut)
            ShortcutExecutionType.MATRIX -> StringResLocalizable(R.string.subtitle_editor_toolbar_matrix_shortcut)
            else -> null
        }

    private fun getBasicSettingsSubtitle(): Localizable =
        enhancedWithVariables(
            if (shortcut.type == ShortcutExecutionType.BROWSER) {
                if (!hasUrl()) {
                    StringResLocalizable(R.string.subtitle_basic_request_settings_url_only_prompt)
                } else {
                    shortcut.url.toLocalizable()
                }
            } else if (shortcut.type == ShortcutExecutionType.MATRIX) {
                if (!hasUrl()) {
                    StringResLocalizable(R.string.subtitle_basic_request_settings_matrix_url_prompt)
                } else {
                    shortcut.url.toLocalizable()
                }
            } else {
                if (!hasUrl()) {
                    StringResLocalizable(R.string.subtitle_basic_request_settings_prompt)
                } else {
                    StringResLocalizable(
                        R.string.subtitle_basic_request_settings_pattern,
                        shortcut.method,
                        shortcut.url,
                    )
                }
            }
        )

    private fun hasUrl() =
        shortcut.url.let { it.isNotEmpty() && it != "http://" && it != "https://" }

    private fun getHeadersSubtitle(): Localizable {
        val count = shortcut.headers.size
        return if (count == 0) {
            StringResLocalizable(R.string.subtitle_request_headers_none)
        } else {
            QuantityStringLocalizable(R.plurals.subtitle_request_headers_pattern, count)
        }
    }

    private fun getRequestBodySubtitle(): Localizable =
        if (shortcut.allowsBody()) {
            when (shortcut.bodyType) {
                RequestBodyType.FORM_DATA,
                RequestBodyType.X_WWW_FORM_URLENCODE,
                -> {
                    val count = shortcut.parameters.size
                    if (count == 0) {
                        StringResLocalizable(R.string.subtitle_request_body_params_none)
                    } else {
                        QuantityStringLocalizable(R.plurals.subtitle_request_body_params_pattern, count)
                    }
                }
                RequestBodyType.FILE -> StringResLocalizable(R.string.subtitle_request_body_file)
                RequestBodyType.IMAGE -> StringResLocalizable(R.string.subtitle_request_body_image)
                RequestBodyType.CUSTOM_TEXT -> if (shortcut.bodyContent.isBlank()) {
                    StringResLocalizable(R.string.subtitle_request_body_none)
                } else {
                    StringResLocalizable(
                        R.string.subtitle_request_body_custom,
                        shortcut.contentType
                            .ifEmpty { ShortcutModel.DEFAULT_CONTENT_TYPE },
                    )
                }
            }
        } else {
            StringResLocalizable(R.string.subtitle_request_body_not_available, shortcut.method)
        }

    private fun getAuthenticationSubtitle(): Localizable =
        StringResLocalizable(
            when (shortcut.authenticationType) {
                ShortcutAuthenticationType.BASIC -> R.string.subtitle_authentication_basic
                ShortcutAuthenticationType.DIGEST -> R.string.subtitle_authentication_digest
                ShortcutAuthenticationType.BEARER -> R.string.subtitle_authentication_bearer
                ShortcutAuthenticationType.NONE -> if (shortcut.clientCertParams != null && !shortcut.acceptAllCertificates) {
                    R.string.subtitle_authentication_client_cert
                } else {
                    R.string.subtitle_authentication_none
                }
            }
        )

    private fun getScriptingSubtitle(): Localizable =
        StringResLocalizable(
            when (shortcut.type) {
                ShortcutExecutionType.SCRIPTING -> R.string.label_scripting_scripting_shortcuts_subtitle
                ShortcutExecutionType.BROWSER -> R.string.label_scripting_browser_shortcuts_subtitle
                else -> R.string.label_scripting_subtitle
            }
        )

    private fun getTriggerShortcutsSubtitle(): Localizable {
        if (shortcut.type != ShortcutExecutionType.TRIGGER) {
            return Localizable.EMPTY
        }
        val count = TriggerShortcutManager.getTriggeredShortcutIdsFromCode(shortcut.codeOnPrepare).size
        return if (count == 0) {
            StringResLocalizable(R.string.label_trigger_shortcuts_subtitle_none)
        } else {
            QuantityStringLocalizable(R.plurals.label_trigger_shortcuts_subtitle, count)
        }
    }

    private fun enhancedWithVariables(localizable: Localizable): Localizable =
        Localizable.create { context ->
            Variables.rawPlaceholdersToVariableSpans(
                localizable.localize(context),
                variablePlaceholderProvider,
                variablePlaceholderColor,
            )
        }

    fun onShortcutIconChanged(icon: ShortcutIcon) {
        if (isSaving) {
            return
        }
        updateViewState {
            copy(shortcutIcon = icon)
        }
        doWithViewState {
            performOperation(
                temporaryShortcutRepository.setIcon(icon)
            )
        }
    }

    fun onShortcutNameChanged(name: String) {
        if (!isInitialized || isSaving) {
            return
        }
        updateViewState {
            copy(shortcutName = name)
        }
        performOperation(
            temporaryShortcutRepository.setName(name)
        )
    }

    fun onShortcutRoomChanged(room: String) {
        if (!isInitialized || isSaving) {
            return
        }
        updateViewState {
            copy(shortcutRoom = room)
        }
        performOperation(
            temporaryShortcutRepository.setRoom(room)
        )
    }

    fun onShortcutDescriptionChanged(description: String) {
        if (!isInitialized || isSaving) {
            return
        }
        updateViewState {
            copy(shortcutDescription = description)
        }
        performOperation(
            temporaryShortcutRepository.setDescription(description)
        )
    }

    fun onTestButtonClicked() {
        if (isSaving) {
            return
        }
        doWithViewState { viewState ->
            if (!viewState.isExecutable) {
                return@doWithViewState
            }
            waitForOperationsToFinish {
                openActivity(ExecuteActivity.IntentBuilder(TEMPORARY_ID))
            }
        }
    }

    fun onSaveButtonClicked() {
        if (isSaving) {
            return
        }
        doWithViewState { viewState ->
            if (!viewState.hasChanges || isSaving) {
                return@doWithViewState
            }
            isSaving = true
            waitForOperationsToFinish {
                trySave()
            }
        }
    }

    private fun trySave() {
        if (shortcut.name.isBlank()) {
            showSnackbar(R.string.validation_name_not_empty, long = true)
            emitEvent(ShortcutEditorEvent.FocusNameInputField)
            isSaving = false
            return
        }
        if (
            (shortcut.type.requiresHttpUrl && !isAcceptableHttpUrl(shortcut.url)) ||
            (shortcut.type.usesUrl && !shortcut.type.requiresHttpUrl && !isAcceptableUrl(shortcut.url))
        ) {
            showSnackbar(R.string.validation_url_invalid, long = true)
            isSaving = false
            return
        }

        save()
    }

    private fun save() {
        val isNewShortcut = shortcutId == null
        val shortcutId = shortcutId ?: newUUID()
        performOperation(
            shortcutRepository.copyTemporaryShortcutToShortcut(shortcutId, categoryId?.takeIf { isNewShortcut })
                .andThen(temporaryShortcutRepository.deleteTemporaryShortcut())
                .doOnError {
                    isSaving = false
                },
        ) {
            onSaveSuccessful(shortcutId)
        }
    }

    private fun onSaveSuccessful(shortcutId: ShortcutId) {
        launcherShortcutManager.updatePinnedShortcut(shortcutId, shortcut.name, shortcut.icon)
        performOperation(widgetManager.updateWidgets(context, shortcutId))
        waitForOperationsToFinish {
            CleanUpWorker.schedule(context)
            finishWithOkResult(
                ShortcutEditorActivity.OpenShortcutEditor.createResult(shortcutId),
            )
        }
    }

    fun onBackPressed() {
        if (isSaving) {
            return
        }
        waitForOperationsToFinish {
            if (hasChanges()) {
                showDiscardDialog()
            } else {
                onDiscardDialogConfirmed()
            }
        }
    }

    private fun showDiscardDialog() {
        dialogState = DialogState.create {
            message(R.string.confirm_discard_changes_message)
                .positive(R.string.dialog_discard) { onDiscardDialogConfirmed() }
                .negative(R.string.dialog_cancel)
                .build()
        }
    }

    private fun onDiscardDialogConfirmed() {
        performOperation(temporaryShortcutRepository.deleteTemporaryShortcut())
        waitForOperationsToFinish {
            CleanUpWorker.schedule(context)
            finish(result = Activity.RESULT_CANCELED)
        }
    }

    fun onBasicRequestSettingsButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(BasicRequestSettingsActivity.IntentBuilder())
    }

    fun onHeadersButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(RequestHeadersActivity.IntentBuilder())
    }

    fun onRequestBodyButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(RequestBodyActivity.IntentBuilder())
    }

    fun onAuthenticationButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(AuthenticationActivity.IntentBuilder())
    }

    fun onResponseHandlingButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(ResponseActivity.IntentBuilder())
    }

    fun onScriptingButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(
            ScriptingActivity.IntentBuilder()
                .shortcutId(shortcutId)
        )
    }

    fun onTriggerShortcutsButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(
            TriggerShortcutsActivity.IntentBuilder()
                .shortcutId(shortcutId)
        )
    }

    fun onExecutionSettingsButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(ExecutionSettingsActivity.IntentBuilder())
    }

    fun onAdvancedSettingsButtonClicked() {
        if (isSaving) {
            return
        }
        openActivity(AdvancedSettingsActivity.IntentBuilder())
    }

    fun onIconClicked() {
        if (isSaving) {
            return
        }
        dialogState = getIconPickerDialog(
            includeFaviconOption = hasUrl(),
            callbacks = object : GetIconPickerDialogUseCase.Callbacks {
                override fun openBuiltInIconSelectionDialog() {
                    dialogState = getBuiltInIconPickerDialog { icon ->
                        onShortcutIconChanged(icon)
                    }
                }

                override fun openCustomIconPicker() {
                    emitEvent(ShortcutEditorEvent.OpenCustomIconPicker)
                }

                override fun fetchFavicon() {
                    onFetchFaviconOptionSelected()
                }

                override fun openIpackPicker() {
                    emitEvent(ShortcutEditorEvent.OpenIpackIconPicker)
                }
            },
        )
    }

    fun onFetchFaviconOptionSelected() {
        if (isSaving) {
            return
        }
        fetchFavicon(shortcut.url)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                updateViewState {
                    copy(iconLoading = true)
                }
            }
            .doFinally {
                updateViewState {
                    copy(iconLoading = false)
                }
            }
            .subscribeOptional(
                { icon ->
                    if (icon != null) {
                        onShortcutIconChanged(icon)
                    } else {
                        showSnackbar(R.string.error_failed_to_fetch_favicon)
                    }
                },
                ::handleUnexpectedError,
            )
            .attachTo(destroyer)
    }

    data class InitData(
        val categoryId: CategoryId?,
        val shortcutId: ShortcutId?,
        val curlCommand: CurlCommand?,
        val executionType: ShortcutExecutionType,
        val recoveryMode: Boolean,
    )
}
