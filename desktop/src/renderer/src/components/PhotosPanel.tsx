import { useEffect, useMemo, useRef, useState } from 'react'
import type {
  GalleryAlbum,
  GalleryDownloadState,
  GalleryKind,
  GalleryPhoto,
  GalleryPreview,
  TransferItem
} from '@shared/protocol'
import { Icon } from './Icon'
import { bytes } from './FilesPanel'

interface Props {
  albums: GalleryAlbum[]
  photos: GalleryPhoto[]
  albumId: string | null
  albumName: string
  nextOffset: number
  hasMore: boolean
  loading: boolean
  thumbs: Record<string, string>
  preview: GalleryPreview | null
  error: string
  connected: boolean
  uploading: number
  kind: GalleryKind
  kinds: GalleryKind[]
  granted: boolean
  partial: boolean
  downloads: Record<string, GalleryDownloadState>
  transfers: TransferItem[]
  onRefresh: () => void
  onSendPhotos: () => void
  onSelectKind: (kind: GalleryKind) => void
  onOpenAlbum: (albumId: string, name: string) => void
  onCloseAlbum: () => void
  onLoadMore: (albumId: string, offset: number) => void
  onRequestThumbs: (photoIds: string[]) => void
  onPreview: (photoId: string, name: string) => void
  onClosePreview: () => void
  onDownload: (photoId: string) => void
}

function duration(value?: number): string {
  if (!value || value <= 0) return ''
  const total = Math.round(value / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

function photoDate(value: number): string {
  if (!value) return ''
  return new Date(value).toLocaleDateString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  })
}

export function PhotosPanel({
  albums,
  photos,
  albumId,
  albumName,
  nextOffset,
  hasMore,
  loading,
  thumbs,
  preview,
  error,
  connected,
  uploading,
  kind,
  kinds,
  granted,
  partial,
  downloads,
  transfers,
  onRefresh,
  onSendPhotos,
  onSelectKind,
  onOpenAlbum,
  onCloseAlbum,
  onLoadMore,
  onRequestThumbs,
  onPreview,
  onClosePreview,
  onDownload
}: Props): JSX.Element {
  const requested = useRef(new Set<string>())
  const [video, setVideo] = useState<GalleryPhoto | null>(null)
  const isVideo = kind === 'video'
  const videoKey = video ? `${kind}:${video.id}` : ''
  const videoDownload = videoKey ? downloads[videoKey] : undefined
  const videoTransfer = video
    ? transfers.find(
        (item) =>
          item.photoId === video.id &&
          item.mediaKind === 'video' &&
          (item.status === 'pending' || item.status === 'active')
      )
    : undefined
  const visibleIds = useMemo(
    () =>
      albumId
        ? photos.map((photo) => photo.id)
        : albums.map((album) => album.coverPhotoId).filter(Boolean),
    [albumId, photos, albums]
  )

  useEffect(() => {
    for (const id of Object.keys(thumbs)) requested.current.delete(id)
    const missing = visibleIds.filter((id) => !thumbs[id] && !requested.current.has(id)).slice(0, 24)
    if (missing.length === 0) return
    for (const id of missing) requested.current.add(id)
    onRequestThumbs(missing)
  }, [visibleIds, thumbs, onRequestThumbs])

  useEffect(() => {
    if (!preview) return
    const close = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClosePreview()
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [preview, onClosePreview])

  useEffect(() => {
    setVideo(null)
  }, [kind, albumId])

  return (
    <div className="photos">
      {albumId ? (
        <>
          <div className="gallery-head">
            <div>
              <button className="link-btn" onClick={onCloseAlbum}>
                <Icon name="arrow_back" size={18} /> Albums
              </button>
              <h2>{albumName}</h2>
            </div>
            <button className="link-btn" onClick={() => onOpenAlbum(albumId, albumName)}>
              <Icon name="refresh" size={18} /> Refresh
            </button>
          </div>

          {photos.length === 0 && !loading ? (
            <GalleryEmpty
              error={error}
              kind={kind}
              granted={granted}
              partial={partial}
              onRefresh={onRefresh}
            />
          ) : (
            <div className="photo-grid">
              {photos.map((photo) => (
                <button
                  key={photo.id}
                  className="photo-tile"
                  onClick={() => (isVideo ? setVideo(photo) : onPreview(photo.id, photo.name))}
                  title={`${photo.name} · ${bytes(photo.size)}`}
                >
                  {thumbs[photo.id] ? (
                    <img src={thumbs[photo.id]} alt={photo.name} loading="lazy" />
                  ) : (
                    <div className="photo-placeholder">{isVideo ? 'Video' : 'Photo'}</div>
                  )}
                  {isVideo && (
                    <span className="video-badge">
                      <Icon name="play_arrow" size={16} />
                      {duration(photo.durationMs)}
                    </span>
                  )}
                  <span className="photo-caption">
                    {isVideo ? duration(photo.durationMs) || photoDate(photo.dateTaken) : photoDate(photo.dateTaken)}
                  </span>
                </button>
              ))}
            </div>
          )}

          {loading && <div className="gallery-loading">Loading from phone…</div>}
          {hasMore && !loading && (
            <div className="gallery-more">
              <button className="btn btn-ghost" onClick={() => onLoadMore(albumId, nextOffset)}>
                <Icon name="expand_more" size={18} /> Load more
              </button>
            </div>
          )}
        </>
      ) : (
        <>
          <div className="gallery-head">
            <div>
              <h2>{isVideo ? 'Videos on your phone' : 'Albums on your phone'}</h2>
              <p className="muted">Previews stay in memory and travel only over your encrypted LAN link.</p>
            </div>
            <div className="gallery-actions">
              {kinds.includes('video') && (
                <div className="notif-view" role="group" aria-label="Media kind">
                  <button
                    type="button"
                    className={kind === 'image' ? 'notif-view-active' : ''}
                    aria-pressed={kind === 'image'}
                    onClick={() => onSelectKind('image')}
                  >
                    Photos
                  </button>
                  <button
                    type="button"
                    className={kind === 'video' ? 'notif-view-active' : ''}
                    aria-pressed={kind === 'video'}
                    onClick={() => onSelectKind('video')}
                  >
                    Videos
                  </button>
                </div>
              )}
              <button
                className="link-btn"
                onClick={onSendPhotos}
                disabled={!connected}
                title={connected ? 'Choose photos or videos to send to the phone' : 'Connect your phone first'}
              >
                <Icon name="upload" size={18} /> Send media
              </button>
              <button className="link-btn" onClick={onRefresh} disabled={loading}>
                {loading ? (
                  'Loading…'
                ) : (
                  <>
                    <Icon name="refresh" size={18} /> Refresh
                  </>
                )}
              </button>
            </div>
          </div>
          {uploading > 0 && (
            <p className="muted gallery-uploading">
              Uploading {uploading} photo{uploading === 1 ? '' : 's'} to your phone…
            </p>
          )}

          {albums.length === 0 && !loading ? (
            <GalleryEmpty
              error={error}
              kind={kind}
              granted={granted}
              partial={partial}
              onRefresh={onRefresh}
            />
          ) : (
            <div className="album-grid">
              {albums.map((album) => (
                <button
                  key={album.id}
                  className="album-card"
                  onClick={() => onOpenAlbum(album.id, album.name)}
                >
                  <div className="album-cover">
                    {thumbs[album.coverPhotoId] ? (
                      <img src={thumbs[album.coverPhotoId]} alt="" loading="lazy" />
                    ) : (
                      <div className="photo-placeholder">Album</div>
                    )}
                  </div>
                  <div className="album-info">
                    <strong>{album.name}</strong>
                    <span>{album.count.toLocaleString()} {isVideo ? 'videos' : 'photos'}</span>
                  </div>
                </button>
              ))}
            </div>
          )}
          {loading && <div className="gallery-loading">Reading albums from phone…</div>}
        </>
      )}

      {video && (
        <div className="photo-lightbox" role="dialog" aria-modal="true" onClick={() => setVideo(null)}>
          <div className="photo-lightbox-card" onClick={(event) => event.stopPropagation()}>
            <div className="photo-lightbox-head">
              <strong title={video.name}>{video.name}</strong>
              <button className="icon-btn" onClick={() => setVideo(null)} aria-label="Close video">
                <Icon name="close" />
              </button>
            </div>
            <div className="photo-preview-body">
              {videoDownload?.status === 'done' && videoDownload.transferId ? (
                <video
                  key={videoDownload.transferId}
                  className="video-player"
                  controls
                  autoPlay
                  playsInline
                  preload="metadata"
                  src={`linkphone-media://media/${videoDownload.transferId}`}
                />
              ) : (
                <>
                  {thumbs[video.id] ? (
                    <img src={thumbs[video.id]} alt={video.name} />
                  ) : (
                    <div className="photo-placeholder">Video</div>
                  )}
                  {videoTransfer ? (
                    <div className="gallery-loading">
                      Downloading{' '}
                      {videoTransfer.size > 0
                        ? Math.round((videoTransfer.received / videoTransfer.size) * 100)
                        : 0}
                      %…
                    </div>
                  ) : videoDownload?.status === 'failed' ? (
                    <div className="gallery-error">
                      {videoDownload.error || 'Download failed'}
                    </div>
                  ) : (
                    <button className="btn" onClick={() => onDownload(video.id)}>
                      <Icon name="play_arrow" size={18} /> Play video
                    </button>
                  )}
                </>
              )}
            </div>
            <div className="photo-lightbox-actions">
              <span className="muted">
                {duration(video.durationMs)} · saved to Downloads/LinkPhone
              </span>
              <button className="btn btn-ghost" onClick={() => onDownload(video.id)}>
                <Icon name="download" size={18} /> Download
              </button>
            </div>
          </div>
        </div>
      )}

      {preview && (
        <div className="photo-lightbox" role="dialog" aria-modal="true" onClick={onClosePreview}>
          <div className="photo-lightbox-card" onClick={(event) => event.stopPropagation()}>
            <div className="photo-lightbox-head">
              <strong title={preview.name}>{preview.name}</strong>
              <button className="icon-btn" onClick={onClosePreview} aria-label="Close preview">
                <Icon name="close" />
              </button>
            </div>
            <div className="photo-preview-body">
              {preview.loading && <div className="gallery-loading">Loading preview…</div>}
              {preview.error && <div className="gallery-error">{preview.error}</div>}
              {preview.dataUrl && <img src={preview.dataUrl} alt={preview.name} />}
            </div>
            <div className="photo-lightbox-actions">
              <span className="muted">Original will be saved to Downloads/LinkPhone</span>
              <button className="btn" onClick={() => onDownload(preview.photoId)}>
                <Icon name="download" size={18} /> Download original
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function GalleryEmpty({
  error,
  kind,
  granted,
  partial,
  onRefresh
}: {
  error: string
  kind: GalleryKind
  granted: boolean
  partial: boolean
  onRefresh: () => void
}): JSX.Element {
  const video = kind === 'video'
  const title = error
    ? video
      ? 'Videos unavailable'
      : 'Photos unavailable'
    : video
      ? !granted
        ? 'Videos need access'
        : partial
          ? 'Only selected videos'
          : 'No videos found'
      : 'No photos found'
  const detail = error
    ? error
    : video
      ? !granted
        ? 'On the phone, open LinkPhone → Setup → Videos → Allow video access, then refresh.'
        : partial
          ? 'Only the videos you selected are visible. Open LinkPhone → Setup → Videos → Allow all videos, then refresh.'
          : 'No videos found on this phone. Check that the videos are in the phone gallery, then refresh.'
      : 'On the phone, allow media access for LinkPhone, then refresh this page.'
  return (
    <div className="empty">
      <div className="empty-art"><Icon name="photo_library" size={34} /></div>
      <h2>{title}</h2>
      <p className="muted">{detail}</p>
      <button type="button" className="link-btn" onClick={onRefresh}>
        <Icon name="refresh" size={18} /> Refresh
      </button>
    </div>
  )
}
