import { useEffect, useMemo, useRef } from 'react'
import type { GalleryAlbum, GalleryPhoto, GalleryPreview } from '@shared/protocol'
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
  onRefresh: () => void
  onOpenAlbum: (albumId: string, name: string) => void
  onCloseAlbum: () => void
  onLoadMore: (albumId: string, offset: number) => void
  onRequestThumbs: (photoIds: string[]) => void
  onPreview: (photoId: string, name: string) => void
  onClosePreview: () => void
  onDownload: (photoId: string) => void
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
  onRefresh,
  onOpenAlbum,
  onCloseAlbum,
  onLoadMore,
  onRequestThumbs,
  onPreview,
  onClosePreview,
  onDownload
}: Props): JSX.Element {
  const requested = useRef(new Set<string>())
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
            <GalleryEmpty error={error} />
          ) : (
            <div className="photo-grid">
              {photos.map((photo) => (
                <button
                  key={photo.id}
                  className="photo-tile"
                  onClick={() => onPreview(photo.id, photo.name)}
                  title={`${photo.name} · ${bytes(photo.size)}`}
                >
                  {thumbs[photo.id] ? (
                    <img src={thumbs[photo.id]} alt={photo.name} loading="lazy" />
                  ) : (
                    <div className="photo-placeholder">Photo</div>
                  )}
                  <span className="photo-caption">{photoDate(photo.dateTaken)}</span>
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
              <h2>Albums on your phone</h2>
              <p className="muted">Previews stay in memory and travel only over your encrypted LAN link.</p>
            </div>
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

          {albums.length === 0 && !loading ? (
            <GalleryEmpty error={error} />
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
                    <span>{album.count.toLocaleString()} photos</span>
                  </div>
                </button>
              ))}
            </div>
          )}
          {loading && <div className="gallery-loading">Reading albums from phone…</div>}
        </>
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

function GalleryEmpty({ error }: { error: string }): JSX.Element {
  return (
    <div className="empty">
      <div className="empty-art"><Icon name="photo_library" size={34} /></div>
      <h2>{error ? 'Photos unavailable' : 'No photos found'}</h2>
      <p className="muted">
        {error || 'On the phone, allow photo access for LinkPhone, then refresh this page.'}
      </p>
    </div>
  )
}
