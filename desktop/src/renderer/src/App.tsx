import { useEffect, useState } from 'react'
import type { AppState } from '@shared/protocol'
import { ClipboardPanel } from './components/ClipboardPanel'
import { DevicePanel } from './components/DevicePanel'
import { FilesPanel } from './components/FilesPanel'
import { MessagesPanel } from './components/MessagesPanel'
import { NotificationsPanel } from './components/NotificationsPanel'
import { PairingCard } from './components/PairingCard'
import { PhotosPanel } from './components/PhotosPanel'
import { UpdatePanel } from './components/UpdatePanel'

type Tab = 'notifications' | 'messages' | 'photos' | 'files' | 'clipboard' | 'settings'

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'notifications', label: 'Notifications' },
  { id: 'messages', label: 'Messages' },
  { id: 'photos', label: 'Photos' },
  { id: 'files', label: 'Files' },
  { id: 'clipboard', label: 'Clipboard' },
  { id: 'settings', label: 'Settings' }
]

export default function App(): JSX.Element {
  const [state, setState] = useState<AppState | null>(null)
  const [tab, setTab] = useState<Tab>('notifications')

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

  if (!state) {
    return (
      <div className="boot">
        <div className="boot-mark" />
        <p>Starting LinkPhone…</p>
      </div>
    )
  }

  const connected = state.devices.filter((d) => d.connected)
  const unread = state.notifications.length

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark" />
          <div>
            <div className="brand-name">LinkPhone</div>
            <div className="brand-sub">Local network · v0.4</div>
          </div>
        </div>

        <DevicePanel devices={state.devices} />

        <nav className="tabs">
          {TABS.map((t) => (
            <button
              key={t.id}
              className={`tab ${tab === t.id ? 'tab-active' : ''}`}
              onClick={() => {
                setTab(t.id)
                if (t.id === 'messages') void window.linkphone.requestSmsThreads()
                if (t.id === 'photos') void window.linkphone.requestGalleryAlbums()
              }}
            >
              <span>{t.label}</span>
              {t.id === 'notifications' && unread > 0 && <span className="badge">{unread}</span>}
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
                : 'Waiting for your phone on this Wi-Fi'}
            </p>
          </div>
          <div className="head-actions">
            {tab === 'files' && (
              <button className="btn" onClick={() => void window.linkphone.sendFiles()}>
                Send file
              </button>
            )}
            {tab === 'notifications' && state.notifications.length > 0 && (
              <button
                className="btn btn-ghost"
                onClick={() => void window.linkphone.clearNotifications()}
              >
                Clear all
              </button>
            )}
          </div>
        </header>

        <section className="content">
          {tab === 'notifications' && (
            <NotificationsPanel
              notifications={state.notifications}
              onDismiss={(key) => void window.linkphone.dismissNotification(key)}
              onReply={(key, actionId, text) =>
                void window.linkphone.sendNotificationReply(key, actionId, text)
              }
            />
          )}
          {tab === 'files' && (
            <FilesPanel
              transfers={state.transfers}
              downloadsDir={state.downloadsDir}
              onSend={() => void window.linkphone.sendFiles()}
              onOpenFolder={() => void window.linkphone.openDownloads()}
            />
          )}
          {tab === 'messages' && (
            <MessagesPanel
              threads={state.smsThreads}
              active={state.smsActive}
              loading={state.smsLoading}
              onRefresh={() => void window.linkphone.requestSmsThreads()}
              onOpen={(threadId, address) =>
                void window.linkphone.openSmsThread(threadId, address)
              }
              onClose={() => {
                void window.linkphone.closeSmsThread()
              }}
              onSend={(threadId, address, body) =>
                void window.linkphone.sendSms(threadId, address, body)
              }
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
              onToggle={(enabled) => void window.linkphone.setClipboardEnabled(enabled)}
              onSend={(text) => void window.linkphone.sendClipboard(text)}
            />
          )}
          {tab === 'settings' && (
            <UpdatePanel
              update={state.desktopUpdate}
              onCheck={() => void window.linkphone.checkDesktopUpdate()}
              onDownload={() => void window.linkphone.downloadDesktopUpdate()}
              onInstall={() => window.linkphone.installDesktopUpdate()}
            />
          )}
        </section>
      </main>
    </div>
  )
}
