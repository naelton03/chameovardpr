import * as Clipboard from 'expo-clipboard';
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
  await Linking.openURL('photos-redirect://');
}

export async function copyPathToClipboard(path: string) {
  await Clipboard.setStringAsync(path);
}
