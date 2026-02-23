import * as Linking from 'expo-linking';
import * as MediaLibrary from 'expo-media-library';

export async function saveToReplayAlbum(localUri: string): Promise<string> {
  const asset = await MediaLibrary.createAssetAsync(localUri);
  let album = await MediaLibrary.getAlbumAsync('ReplayCam');
  if (!album) {
    album = await MediaLibrary.createAlbumAsync('ReplayCam', asset, false);
    return album.title;
  }
  await MediaLibrary.addAssetsToAlbumAsync([asset], album, false);
  return album.title;
}

export async function openVideoGalleryQuickAccess() {
  // Fallback universal: abre configurações/URI tratável no device.
  await Linking.openURL('photos-redirect://');
}
