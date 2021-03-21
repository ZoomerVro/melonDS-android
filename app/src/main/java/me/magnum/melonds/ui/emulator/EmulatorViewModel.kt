package me.magnum.melonds.ui.emulator

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import me.magnum.melonds.common.UriFileHandler
import me.magnum.melonds.domain.model.*
import me.magnum.melonds.domain.repositories.CheatsRepository
import me.magnum.melonds.domain.repositories.LayoutsRepository
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.extensions.addTo
import me.magnum.melonds.impl.FileRomProcessorFactory
import me.magnum.melonds.ui.emulator.exceptions.RomLoadException
import me.magnum.melonds.ui.emulator.exceptions.SramLoadException
import java.io.File
import java.util.*

class EmulatorViewModel @ViewModelInject constructor(
        private val settingsRepository: SettingsRepository,
        private val romsRepository: RomsRepository,
        private val cheatsRepository: CheatsRepository,
        private val fileRomProcessorFactory: FileRomProcessorFactory,
        private val layoutsRepository: LayoutsRepository,
        private val uriFileHandler: UriFileHandler
) : ViewModel() {

    private val disposables = CompositeDisposable()
    private var layoutLoadDisposable: Disposable? = null
    private val layoutLiveData = MutableLiveData<LayoutConfiguration>()

    fun getLayout(): LiveData<LayoutConfiguration> {
        return layoutLiveData
    }

    fun loadLayoutForRom(rom: Rom) {
        val romLayoutId = rom.config.layoutId
        val layoutObservable = if (romLayoutId == null) {
            getGlobalLayoutObservable()
        } else {
            // Load and observe ROM layout but switch to global layout if not found
            layoutsRepository.getLayout(romLayoutId)
                    .flatMapObservable { layoutsRepository.observeLayout(romLayoutId) }
                    .switchIfEmpty { getGlobalLayoutObservable() }
        }

        layoutLoadDisposable?.dispose()
        layoutLoadDisposable = layoutObservable.subscribeOn(Schedulers.io())
                .subscribe {
                    layoutLiveData.postValue(it)
                }
    }

    fun loadLayoutForFirmware() {
        layoutLoadDisposable?.dispose()
        layoutLoadDisposable = getGlobalLayoutObservable()
                .subscribeOn(Schedulers.io())
                .subscribe {
                    layoutLiveData.postValue(it)
                }
    }

    fun getRomSearchDirectory(): Uri? {
        return settingsRepository.getRomSearchDirectories().firstOrNull()
    }

    fun getDsBiosDirectory(): Uri? {
        return settingsRepository.getDsBiosDirectory()
    }

    fun getDsiBiosDirectory(): Uri? {
        return settingsRepository.getDsiBiosDirectory()
    }

    private fun getGlobalLayoutObservable(): Observable<LayoutConfiguration> {
        return settingsRepository.observeSelectedLayoutId()
                .startWith(settingsRepository.getSelectedLayoutId())
                .switchMap { layoutId ->
                    layoutsRepository.getLayout(layoutId)
                            .flatMapObservable { layoutsRepository.observeLayout(layoutId).startWith(it) }
                            .switchIfEmpty { layoutsRepository.observeLayout(LayoutConfiguration.DEFAULT_ID) }
                }
    }

    fun isTouchHapticFeedbackEnabled(): Boolean {
        return settingsRepository.isTouchHapticFeedbackEnabled()
    }

    fun getRomLoader(rom: Rom): Single<Pair<Rom, Uri>> {
        val fileRomProcessor = fileRomProcessorFactory.getFileRomProcessorForDocument(rom.uri)
        return fileRomProcessor?.getRealRomUri(rom)?.map { rom to it } ?: Single.error(RomLoadException("Unsupported ROM file extension"))
    }

    fun getRomInfo(rom: Rom): RomInfo? {
        val fileRomProcessor = fileRomProcessorFactory.getFileRomProcessorForDocument(rom.uri)
        return fileRomProcessor?.getRomInfo(rom)
    }

    fun getRomSramFile(rom: Rom): Uri {
        val rootDirUri = settingsRepository.getSaveFileDirectory(rom)

        val rootDocument = uriFileHandler.getUriTreeDocument(rootDirUri)
        val romDocument = uriFileHandler.getUriDocument(rom.uri)
        val romFileName = romDocument?.name ?: throw SramLoadException("Cannot determine SRAM file name")
        val sramFileName = romFileName.replaceAfterLast('.', "sav", "$romFileName.sav")
        val sramDocument = rootDocument?.findFile(sramFileName)
        return sramDocument?.uri ?: rootDocument?.createFile("*/*", sramFileName)?.let {
            // Create the file and delete it immediately. By simply creating a file with a size of 0, melonDS would assume that to be the SRAM size of the ROM, which would
            // prevent save files from working properly. Instead, we create a file to obtain its URI and delete it so that melonDS assumes that there is no save. When saving
            // for the first time, the proper file will be created.
            it.delete()
            it.uri
        } ?: throw SramLoadException("Cannot create temporary SRAM file")
    }

    fun getRomSaveStateSlots(rom: Rom): List<SaveStateSlot> {
        val saveStateDirectoryUri = settingsRepository.getSaveStateDirectory(rom) ?: return emptyList()
        val saveStateDirectoryDocument = uriFileHandler.getUriTreeDocument(saveStateDirectoryUri) ?: return emptyList()
        val romDocument = uriFileHandler.getUriDocument(rom.uri)!!
        val romFileName = romDocument.name?.substringBeforeLast('.') ?: return emptyList()

        val saveStateSlots = mutableListOf<SaveStateSlot>()
        val directoryFiles = saveStateDirectoryDocument.listFiles()
        for (i in 1..8) {
            val saveStateName = "$romFileName.ml$i"
            val saveStateDocument = directoryFiles.find {
                it.name == saveStateName
            }

            if (saveStateDocument?.isFile == true) {
                saveStateSlots.add(SaveStateSlot(i, true, Date(saveStateDocument.lastModified())))
            } else {
                saveStateSlots.add(SaveStateSlot(i, false, null))
            }
        }

        return saveStateSlots
    }

    fun getRomSaveStateSlotUri(rom: Rom, slot: Int): Uri {
        val saveStateDirectoryUri = settingsRepository.getSaveStateDirectory(rom) ?: throw SramLoadException("Could not determine save slot parent directory")
        val saveStateDirectoryDocument = uriFileHandler.getUriTreeDocument(saveStateDirectoryUri) ?: throw SramLoadException("Could not create save slot parent directory")

        val romDocument = uriFileHandler.getUriDocument(rom.uri)!!
        val romFileName = romDocument.name?.substringBeforeLast('.') ?: throw SramLoadException("Could not determine ROM file name")
        val saveStateName = "$romFileName.ml$slot"
        val saveStateFile = saveStateDirectoryDocument.findFile(saveStateName)

        return if (saveStateFile != null) {
            saveStateFile.uri
        } else {
            saveStateDirectoryDocument.createFile("*/*", saveStateName)?.uri ?: throw SramLoadException("Could not create save state file")
        }
    }

    fun getRomAtPath(path: String): Single<Rom> {
        val romDocument = DocumentFile.fromFile(File(path))
        return romsRepository.getRomAtPath(path).defaultIfEmpty(Rom(path, romDocument.uri, RomConfig())).toSingle()
    }

    fun getRomAtUri(uri: Uri): Single<Rom> {
        val romDocument = uriFileHandler.getUriDocument(uri) ?: return Single.error(RomLoadException("Could not create ROM document"))
        return romsRepository.getRomAtUri(uri).defaultIfEmpty(Rom(uri.toString(), romDocument.uri, RomConfig())).toSingle()
    }

    fun getEmulatorConfigurationForRom(rom: Rom): EmulatorConfiguration {
        val baseConfiguration = settingsRepository.getEmulatorConfiguration()
        return baseConfiguration.copy(
                consoleType = getRomOptionOrDefault(rom.config.runtimeConsoleType, baseConfiguration.consoleType),
                micSource = getRomOptionOrDefault(rom.config.runtimeMicSource, baseConfiguration.micSource)
        )
    }

    fun getEmulatorConfigurationForFirmware(consoleType: ConsoleType): EmulatorConfiguration {
        return settingsRepository.getEmulatorConfiguration().copy(
                useCustomBios = true, // Running a firmware requires a custom BIOS
                consoleType = consoleType
        )
    }

    private fun <T, U> getRomOptionOrDefault(romOption: T, default: U): U where T : RuntimeEnum<T, U> {
        return if (romOption.getDefault() == romOption)
            default
        else
            romOption.getValue()
    }

    fun getRomEnabledCheats(romInfo: RomInfo): LiveData<List<Cheat>> {
        val liveData = MutableLiveData<List<Cheat>>()

        if (!settingsRepository.areCheatsEnabled()) {
            liveData.value = emptyList()
        } else {
            cheatsRepository.getRomEnabledCheats(romInfo).subscribe { cheats ->
                liveData.postValue(cheats)
            }.addTo(disposables)
        }

        return liveData
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
        layoutLoadDisposable?.dispose()
    }
}