import { useEffect, useRef, useState } from 'react'
import type { AppState } from '@shared/protocol'
import logo from './assets/logo.png'
import { Icon, type IconName } from './components/Icon'
import { ClipboardPanel } from './components/ClipboardPanel'
import { CallsPanel } from './components/CallsPanel'
import { DevicePanel } from './components/DevicePanel'
import { FilesPanel } from './components/FilesPanel'
import { MessagesPanel } from './components/MessagesPanel'
import { NotificationsPanel } from './components/NotificationsPanel'
import { PairingCard } from './components/PairingCard'
import { PhotosPanel } from './components/PhotosPanel'
import { StartupPanel } from './components/StartupPanel'
import { UpdatePanel } from './components/UpdatePanel'

type Tab =
  | 'notifications'
  | 'messages'
  | 'calls'
  | 'photos'
  | 'files'
  | 'clipboard'
  | 'settings'

const TABS: Array<{ id: Tab; label: string; icon: IconName }> = [
  { id: 'notifications', label: 'Notifications', icon: 'notifications' },
  { id: 'messages', label: 'Messages', icon: 'sms' },
  { id: 'calls', label: 'Calls', icon: 'call' },
  { id: 'photos', label: 'Photos', icon: 'photo_library' },
  { id: 'files', label: 'Files', icon: 'folder' },
  { id: 'clipboard', label: 'Clipboard', icon: 'content_paste' },
  { id: 'settings', label: 'Settings', icon: 'settings' }
]

export default function App(): JSX.Element {
  const [state, setState] = useState<AppState | null>(null)
  const [tab, setTab] = useState<Tab>('notifications')
  const [dragActive, setDragActive] = useState(false)
  const [dropNotice, setDropNotice] = useState('')
  const dragDepth = useRef(0)
  const connectedRef = useRef(false)
  connectedRef.current = (state?.devices ?? []).some((device) => device.connected)

  useEffect(() => {
    let alive = true
    void window.linkphone.getState().then((next) => {
      if (alive) setState(next)
    })
    const off = window.linkphone.onState((next) => setState(next))
    return () => {
      alive = false
      off()
    }
  }, [])

  useEffect(() => {
    const off = window.linkphone.onNavigate((next) => setTab(next))
    void window.linkphone.uiReady().then((next) => {
      if (next) setTab(next)
    })
    return off
  }, [])

  useEffect(() => {
    if (!dropNotice) return
    const timer = setTimeout(() => setDropNotice(''), 5000)
    return () => clearTimeout(timer)
  }, [dropNotice])

  useEffect(() => {
    const hasFiles = (event: DragEvent): boolean =>
      Array.from(event.dataTransfer?.types ?? []).includes('Files')
    const onDragEnter = (event: DragEvent): void => {
      if (!hasFiles(event)) return
      event.preventDefault()
      dragDepth.current += 1
      setDragActive(true)
    }
    const onDragOver = (event: DragEvent): void => {
      if (!hasFiles(event)) return
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    }
    const onDragLeave = (event: DragEvent): void => {
      if (event.relatedTarget === null) {
        dragDepth.current = 0
        setDragActive(false)
        return
      }
      if (!hasFiles(event)) return
      dragDepth.current = Math.max(0, dragDepth.current - 1)
      if (dragDepth.current === 0) setDragActive(false)
    }
    const onDragEnd = (): void => {
      dragDepth.current = 0
      setDragActive(false)
    }
    const onDrop = (event: DragEvent): void => {
      if (!hasFiles(event)) return
      event.preventDefault()
      dragDepth.current = 0
      setDragActive(false)
      if (!connectedRef.current) {
        setDropNotice('Connect your phone to send files')
        return
      }
      const files = Array.from(event.dataTransfer?.files ?? [])
      const paths = files
        .map((file) => window.linkphone.pathForFile(file))
        .filter((value) => value.length > 0)
      if (paths.length === 0) {
        setDropNotice('Folders and virtual files can’t be sent')
        return
      }
      void window.linkphone
        .sendFilesFromPaths(paths)
        .then((accepted) => {
          if (accepted === 0) setDropNotice('Those files can’t be sent')
          else if (accepted < files.length) {
            setDropNotice(`Queued ${accepted} of ${files.length} files for your phone`)
          } else {
            setDropNotice(`Queued ${accepted} file${accepted === 1 ? '' : 's'} for your phone`)
          }
        })
        .catch(() => setDropNotice('Could not send those files'))
    }
    document.addEventListener('dragenter', onDragEnter)
    document.addEventListener('dragover', onDragOver)
    document.addEventListener('dragleave', onDragLeave)
    document.addEventListener('dragend', onDragEnd)
    document.addEventListener('drop', onDrop)
    return () => {
      document.removeEventListener('dragenter', onDragEnter)
      document.removeEventListener('dragover', onDragOver)
      document.removeEventListener('dragleave', onDragLeave)
      document.removeEventListener('dragend', onDragEnd)
      document.removeEventListener('drop', onDrop)
    }
  }, [])

  const dropUi = (
    <>
      {dragActive && (
        <div className="drop-overlay">
          <div className="drop-card">
            <Icon name="upload" size={34} />
            <strong>Drop files to send to your phone</strong>
            <span className="muted">Release to send</span>
          </div>
        </div>
      )}
      {dropNotice && <div className="drop-notice">{dropNotice}</div>}
    </>
  )

  if (!state) {
    return (
      <>
        <div className="boot">
          <div className="boot-mark"><img src={logo} alt="" /></div>
          <p>Starting LinkPhone…</p>
        </div>
        {dropUi}
      </>
    )
  }

  const connected = state.devices.filter((d) => d.connected)
  const unread = state.notifications.length

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><img src={logo} alt="" /></div>
          <div>
            <div className="brand-name">LinkPhone</div>
            <div className="brand-sub">Local network · v0.8.2</div>
          </div>
        </div>

        <DevicePanel devices={state.devices} />

        <nav className="tabs">
          {TABS.map((t) => (
            <button
              key={t.id}
              className={`tab ${tab === t.id ? 'tab-active' : ''}`}
              aria-current={tab === t.id ? 'page' : undefined}
              onClick={() => {
                setTab(t.id)
                if (t.id === 'messages') {
                  void window.linkphone.requestSmsThreads()
                  void window.linkphone.requestSims()
                }
                if (t.id === 'calls') {
                  void window.linkphone.requestCallHistory()
                  void window.linkphone.requestContacts()
                  void window.linkphone.requestSmsThreads()
                  void window.linkphone.requestSims()
                }
                if (t.id === 'photos') void window.linkphone.requestGalleryAlbums()
              }}
            >
              <span className="tab-main">
                <Icon name={t.icon} />
                <span>{t.label}</span>
              </span>
              {t.id === 'notifications' && unread > 0 && <span className="badge">{unread}</span>}
              {t.id === 'calls' && state.phoneCalls.some((call) => call.phase === 'ringing') && (
                <span className="call-tab-ring" title="Incoming call" />
              )}
              {t.id === 'settings' &&
                (state.desktopUpdate.status === 'available' ||
                  state.desktopUpdate.status === 'ready') && (
                <span className="update-dot" title="Update available" />
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-foot">
          <PairingCard
            code={state.pairingCode}
            serverId={state.serverId}
            serverName={state.serverName}
            port={state.port}
            addresses={state.addresses}
            addresses6={state.addresses6}
            onNewCode={() => void window.linkphone.newPairingCode()}
          />
        </div>
      </aside>

      <main className="main">
        <header className="main-head">
          <div>
            <h1>{TABS.find((t) => t.id === tab)?.label}</h1>
            <p className="muted">
              {connected.length > 0
                ? `${connected.map((d) => d.name).join(', ')} connected`
                : state.devices.length > 0
                  ? `${state.devices[0].name} is offline — open LinkPhone on your phone`
                  : 'Waiting for your phone on this Wi-Fi'}
            </p>
          </div>
          <div className="head-actions">
            {tab === 'files' && (
              <button
                className="btn"
                disabled={connected.length === 0}
                title={
                  connected.length === 0
                    ? 'Connect your phone to send files'
                    : 'Choose files to send to your phone'
                }
                onClick={() => void window.linkphone.sendFiles()}
              >
                <Icon name="upload" size={18} /> Send file
              </button>
            )}
          </div>
        </header>

        <section className="content">
          {tab === 'notifications' && (
            <NotificationsPanel
              notifications={state.notifications}
              muted={state.mutedNotifications}
              onDismiss={(key) => void window.linkphone.dismissNotification(key)}
              onReply={(key, actionId, text) =>
                void window.linkphone.sendNotificationReply(key, actionId, text)
              }
              onAction={(key, actionId) =>
                void window.linkphone.sendNotificationAction(key, actionId)
              }
              onMuteApp={(pkg, app) =>
                void window.linkphone.setNotificationMuted(pkg, app, true)
              }
              onUnmuteApp={(pkg, app) =>
                void window.linkphone.setNotificationMuted(pkg, app, false)
              }
              onClearApp={(pkg, app) =>
                void window.linkphone.clearNotificationApp(pkg, app)
              }
              onLoadHistory={() => window.linkphone.getNotificationHistory()}
              onClearHistory={() => window.linkphone.clearNotificationHistory()}
              onClearAll={() => window.linkphone.clearNotifications()}
              connected={connected.length > 0}
              media={state.media}
              onMediaAction={(action) => void window.linkphone.sendMediaAction(action)}
            />
          )}
          {tab === 'files' && (
            <FilesPanel
              transfers={state.transfers}
              downloadsDir={state.downloadsDir}
              connected={connected.length > 0}
              onSend={() => void window.linkphone.sendFiles()}
              onOpenFolder={() => void window.linkphone.openDownloads()}
            />
          )}
          {tab === 'messages' && (
            <MessagesPanel
              threads={state.smsThreads}
              active={state.smsActive}
              loading={state.smsLoading}
              sims={state.phoneSims}
              onRefresh={() => void window.linkphone.requestSmsThreads()}
              onOpen={(threadId, address) =>
                void window.linkphone.openSmsThread(threadId, address)
              }
              onClose={() => {
                void window.linkphone.closeSmsThread()
              }}
              onSend={(threadId, address, body, subscriptionId) =>
                window.linkphone.sendSms(threadId, address, body, subscriptionId)
              }
              onStartConversation={(address, body, subscriptionId) =>
                window.linkphone.startSmsConversation(address, body, subscriptionId)
              }
            />
          )}
          {tab === 'calls' && (
            <CallsPanel
              calls={state.phoneCalls}
              outgoingReady={state.callOutgoingReady}
              controlReady={state.callControlReady}
              callPlaced={state.callPlaced}
              speaker={state.callSpeaker}
              error={state.callError}
              connected={connected.length > 0}
              history={state.callHistory}
              historyGranted={state.callLogGranted}
              historyLoading={state.callLogLoading}
              contacts={state.phoneContacts}
              threads={state.smsThreads}
              contactsGranted={state.contactsGranted}
              contactsLoading={state.contactsLoading}
              smsLoading={state.smsLoading}
              callAccounts={state.callAccounts}
              onPlace={(number, accountId) =>
                void window.linkphone.placeCall(number, accountId)
              }
              onAnswer={(callId) => void window.linkphone.answerCall(callId)}
              onReject={(callId) => void window.linkphone.rejectCall(callId)}
              onDisconnect={(callId) => void window.linkphone.disconnectCall(callId)}
              onSpeaker={(enabled) => void window.linkphone.setCallSpeaker(enabled)}
              onRefreshHistory={() => void window.linkphone.requestCallHistory()}
              onRefreshContacts={() => void window.linkphone.requestContacts()}
              onCall={(number, accountId) =>
                void window.linkphone.placeCall(number, accountId)
              }
              onMessage={(threadId, address) => {
                setTab('messages')
                void window.linkphone.openSmsThread(threadId, address)
              }}
              onDismissPlaced={() => void window.linkphone.dismissCallPlaced()}
              onDismissError={() => void window.linkphone.dismissCallError()}
            />
          )}
          {tab === 'photos' && (
            <PhotosPanel
              albums={state.galleryAlbums}
              photos={state.galleryPhotos}
              albumId={state.galleryAlbumId}
              albumName={state.galleryAlbumName}
              nextOffset={state.galleryNextOffset}
              hasMore={state.galleryHasMore}
              loading={state.galleryLoading}
              thumbs={state.galleryThumbs}
              preview={state.galleryPreview}
              error={state.galleryError}
              onRefresh={() => void window.linkphone.requestGalleryAlbums()}
              onOpenAlbum={(albumId, name) =>
                void window.linkphone.openGalleryAlbum(albumId, name)
              }
              onCloseAlbum={() => void window.linkphone.closeGalleryAlbum()}
              onLoadMore={(albumId, offset) =>
                void window.linkphone.loadMoreGallery(albumId, offset)
              }
              onRequestThumbs={(photoIds) =>
                void window.linkphone.requestGalleryThumbnails(photoIds)
              }
              onPreview={(photoId, name) =>
                void window.linkphone.requestGalleryPreview(photoId, name)
              }
              onClosePreview={() => void window.linkphone.closeGalleryPreview()}
              onDownload={(photoId) => void window.linkphone.downloadGalleryPhoto(photoId)}
            />
          )}
          {tab === 'clipboard' && (
            <ClipboardPanel
              enabled={state.clipboardEnabled}
              connected={connected.length > 0}
              onToggle={(enabled) => void window.linkphone.setClipboardEnabled(enabled)}
              onSend={(text) => window.linkphone.sendClipboard(text)}
            />
          )}
          {tab === 'settings' && (
            <div className="settings-panel">
              <StartupPanel
                supported={state.launchAtLoginSupported}
                enabled={state.launchAtLogin}
                onToggle={(enabled) => void window.linkphone.setLaunchAtLogin(enabled)}
              />
              <UpdatePanel
                update={state.desktopUpdate}
                onCheck={() => void window.linkphone.checkDesktopUpdate()}
                onDownload={() => void window.linkphone.downloadDesktopUpdate()}
                onInstall={() => window.linkphone.installDesktopUpdate()}
              />
            </div>
          )}
        </section>
      </main>
      {dropUi}
    </div>
  )
}
