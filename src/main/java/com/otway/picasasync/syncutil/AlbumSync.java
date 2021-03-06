/*
    Copyright 2015 Mark Otway

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.otway.picasasync.syncutil;

import com.google.gdata.data.DateTime;
import com.google.gdata.data.media.mediarss.MediaKeywords;
import com.google.gdata.data.photos.AlbumEntry;
import com.google.gdata.data.photos.PhotoEntry;
import com.google.gdata.util.ServiceException;
import com.otway.picasasync.config.Settings;
import com.otway.picasasync.metadata.ImageInformation;
import com.otway.picasasync.metadata.UniquePhoto;
import com.otway.picasasync.picasaini.PicasaIniParser;
import com.otway.picasasync.utils.FileUtilities;
import com.otway.picasasync.webclient.PicasawebClient;
import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Synchronisation class - represents a work item of one album.
 */
public class AlbumSync
{
    private static final Logger log = Logger.getLogger(AlbumSync.class);
    private AlbumEntry albumEntry;
    private final File localFolder;
    private final SyncManager syncManager;
    private final Settings settings;

    @Override
    public String toString() {
        return String.format("%s (%d)", localFolder.getName(), localFolder.lastModified() );
    }

    public AlbumSync(AlbumEntry album, File localFolder, SyncManager manager, Settings settings) throws ServiceException {
        this.albumEntry = album;
        this.localFolder = localFolder;
        this.syncManager = manager;
        this.settings = settings;
    }


    public Date localChangeDate() {

        Date localDate = FileUtilities.getLatestDatefromDir( localFolder );

        if( albumEntry != null )
        {
            DateTime updateDate = albumEntry.getUpdated();

            if (updateDate != null)
            {
                Date remoteDate = new Date(updateDate.getValue());

                // TODO: More exists calls
                // If the remote date is more recent, use that, otherwise use the localdate
                if (!localFolder.exists() || remoteDate.after(localDate))
                    return remoteDate;
            }
        }
        return localDate;
    }

    public boolean getHasAlbum() { return albumEntry != null; }

    public String getAlbumName() throws ServiceException
    {
        try{
            return albumEntry.getTitle().getPlainText();
        }
        catch( Exception ex )
        {
            log.error("Invalid title!");
            throw new ServiceException(ex);
        }
    }

    public void process( PicasawebClient webClient, LocalDateTime oldestDate, AlbumEntry recycleAlbum )
                            throws IOException, ServiceException
    {
        log.info( "Beginning sync for album: " + getAlbumName() + " (Name: " + albumEntry.getName() + ")" );
        syncManager.updateProgress(String.format("Synchronising %s...", getAlbumName()));

        boolean isAutoBackup = PicasawebClient.isInstantUpload(albumEntry);

        // PicasaIniParser parser = PicasaIniParser.getPicasaIni( localFolder );

        // Calculate what images we need to upload - i.e., the diff
        // (including size and date) of the local images vs online
        List<ImageSync> images = buildImageList(webClient, localFolder, albumEntry, oldestDate);

        List<ImageSync> downloads = new ArrayList<ImageSync>();
        List<ImageSync> uploads = new ArrayList<ImageSync>();
        List<ImageSync> deletes = new ArrayList<ImageSync>();

        for( ImageSync image : images )
        {
            if( isDeletion(image) )
            {
                deletes.add( image );
                continue;
            }

            switch( image.evaluateAction( settings, isAutoBackup ) )
            {
                case upload:
                    uploads.add(image);
                    break;
                case download:
                    downloads.add(image);
                    break;
                default:
                    log.debug("Photo " + image.getName() + " was unchanged.");
            }
        }

        // Now, do the upload
        for (ImageSync image : uploads)
        {
            // Check that the album exists, create it and save if it doesn't.
            albumEntry = webClient.prepareRemoteAlbum(albumEntry);

            syncManager.updateProgress(String.format("Uploading %s : %s...", getAlbumName(), image.getName()));

            if( webClient.uploadImageToAlbum(image.getLocalFile(), image.getRemotePhoto(), albumEntry, image.getLocalMd5CheckSum() ) )
            {
                syncManager.getSyncState().addStats(0, 1, 0);
            }
            else
                syncManager.getSyncState().addStats(0, 0, 1);

            if (syncManager.getSyncState().getIsCancelled())
                break;
        }

        if( uploads.size() > 0 )
        {
            // If we had any uploads for this album, set the remote album
            // entry based on the most recent 'date taken' from the local
            // metadata
            webClient.setAlbumDateFromFolder(localFolder, albumEntry);
        }

        // Now download - but only if we have space on the local disk.
        for (ImageSync image : downloads)
        {
            if (!checkDiskSpace())
                break;

            syncManager.updateProgress(String.format("Downloading %s : %s...", getAlbumName(), image.getName()));

            if (syncManager.getSyncState().getIsCancelled())
                break;

            // And finally, download any new images - if the remote version is newer
            if( downloadImage(image, webClient) )
            {
                syncManager.getSyncState().addStats(1, 0, 0);
            }
            else
            {
                syncManager.getSyncState().addStats(0, 0, 1);
                syncManager.updateProgress( "Download error. Aborting." );
                break;
            }
        }

        // Now clean up any images that have been marked for deletion.
        for (ImageSync image : deletes)
        {
            syncManager.recyclePhoto( image );
        }
    }

    private boolean isDeletion(ImageSync image)
    {
        if( image.getRemotePhoto() != null )
        {
            MediaKeywords keywords = image.getRemotePhoto().getMediaKeywords();

            List<String> tags = keywords.getKeywords();

            for (String tag : tags)
            {
                if (tag.equals("delete"))
                    return true;
            }

            PhotoEntry photo = image.getRemotePhoto();

            if( photo != null )
            {
                UniquePhoto up = new UniquePhoto( photo );

                // See if this image is in the 'Recycle Bin' album
                if (syncManager.isDeleted( up ))
                    return true;
            }
        }

        // No remote photo. See if the local folder has a 'deleted' tag
        ImageInformation localInfo = ImageInformation.safeReadImageInformation( image.getLocalFile() );

        if( localInfo != null && localInfo.getDeleteTag() )
            return true;

        // It's possible the photo may be sitting in the recycle bin in the
        // cloud because it's already been deleted. So we'll have a look and
        // see if we can identify it as having already been deleted.

        try
        {
            UniquePhoto up = new UniquePhoto(image.getLocalFile());
            if (syncManager.isDeleted(up))
                return true;

        }
        catch( Exception ex )
        {
            // Don't care
        }

        return false;
    }

    private boolean checkDiskSpace()
    {
        final int MIN_FREE_DISK_SPACE_PERCENTAGE = 2;

        double freeSpace = settings.getPhotoRootFolder().getFreeSpace();
        double totalSpace = settings.getPhotoRootFolder().getTotalSpace();

        double percentage = (freeSpace / totalSpace) * 100.0;
        if( percentage > MIN_FREE_DISK_SPACE_PERCENTAGE)
            return true;

        syncManager.getSyncState().setStatus("Available disk space was less than " + MIN_FREE_DISK_SPACE_PERCENTAGE + "%. Skipping downloads.");
        log.warn("Available disk space was less than " + MIN_FREE_DISK_SPACE_PERCENTAGE + "%. Skipping downloads.");
        return false;
    }

    private List<ImageSync> buildImageList(PicasawebClient webClient, File localFolder, AlbumEntry albumEntry, final LocalDateTime oldestDate) throws IOException, ServiceException {

        List<ImageSync> allImages = new ArrayList<ImageSync>();

        syncManager.getSyncState().setStatus("Querying Google for album " + albumEntry.getTitle().getPlainText() );

        // Get the list of remote photos
        List<PhotoEntry> photos = webClient.getPhotos( albumEntry );

        // Deal with the fact that an album can have multiple images with the same local filename.
        HashMap<String, List<PhotoEntry>> fileGroups = new HashMap<String, List<PhotoEntry>>();

        for( PhotoEntry photo : photos )
        {
            String imageName = photo.getTitle().getPlainText().toLowerCase();

            if( FilenameUtils.getExtension( imageName ).toLowerCase().equals(".mov") )
            {
                log.info( "Skipping file " + imageName + " with .mov file extension.");
                continue;
            }

            List<PhotoEntry> photoList = null;

            if( ! fileGroups.containsKey( imageName ))
            {
                photoList = new ArrayList<PhotoEntry>();
                fileGroups.put( imageName, photoList );
            }
            else
                photoList = fileGroups.get( imageName );

            photoList.add( photo );
        }

        // So now we have a map of image name => List of photo entries which use that name.
        List<PhotoEntry> nonDupePhotos = new ArrayList<PhotoEntry>();

        int dupesDiscarded = 0;
        for (List<PhotoEntry> list : fileGroups.values()) {
            String maxId = null;
            PhotoEntry photoToUse = null;

            for( PhotoEntry photo : list )
            {
                String id = PicasawebClient.getPhotoId(photo);

                // We'll arbitrarily pick the one with the lowest ID, and ignore the rest.
                if( maxId == null || maxId.compareTo( id ) == -1 )
                {
                    maxId = id;
                    photoToUse = photo;
                }
            }

            nonDupePhotos.add( photoToUse );

            dupesDiscarded += list.size() - 1;
        }

        if( dupesDiscarded > 0 )
        {
            log.info("Ignoring " + dupesDiscarded + " duplicate photos of " + photos.size() + " from album " + getAlbumName() );
        }

        List<ImageSync> remoteImages = new ArrayList<ImageSync>();

        for( PhotoEntry photo : nonDupePhotos )
        {
            String imageFile = photo.getTitle().getPlainText();

            if( settings.getExcludeVideos() && photo.getMediaContents().size() > 1 )
            {
                log.info("Exclude Video enabled: skipping " + imageFile);
                continue;
            }

            File localFileName = new File( localFolder, imageFile );
            remoteImages.add(new ImageSync( photo, localFileName));
        }

        log.debug(remoteImages.size() + " remote images found in " + albumEntry.getTitle().getPlainText());

        // Get the local file list
        File[] files = localFolder.listFiles(
            new FilenameFilter() {
                public boolean accept(File current, String name) {
                    File file = new File(current, name);
                    return file.isFile() && !file.isHidden();
                }
            }
        );

        List<ImageSync> localFiles = new ArrayList<ImageSync>();

        if( files != null && files.length > 0 )
        {
            log.info(files.length + " local files found in " + localFolder);

            // Now, pull out all the local files that aren't in the list.
            // These are the new files that we'll upload
            for (File localFile : files)
            {
                if (!fileGroups.containsKey(localFile.getName().toLowerCase()))
                {
                    localFiles.add(new ImageSync(null, localFile));
                }
            }
        }

        log.debug(localFiles.size() + " local images found in " + localFolder);
        allImages.addAll( localFiles );

        // Add the remote images after. Uploads are higher priority than downloads
        allImages.addAll( remoteImages );

        log.debug(allImages.size() + " images found (new local + remote)");

        // And finally, filter out anything that's too old.
        List<ImageSync> result = new ArrayList<ImageSync>();

        for( ImageSync image : allImages )
            if( image.newerThan(oldestDate))
                result.add( image );

        log.debug(result.size() + " total images after date filter applied." );

        return result;
    }

    // Given a local folder, enumerate the files within it and then
    // set the folder last-modified date to the date-taken of the
    // most recent photo
    private void updateFolderTimeStamp(File localFolder) {

        long maxDate = 0;

        File[] files = localFolder.listFiles(
                new FilenameFilter() {
                    public boolean accept(File current, String name) {
                        File file = new File(current, name);
                        return file.isFile() && !file.isHidden();
                    }
                }
        );

        if( files != null ) {
            for( File file : files )
                if ( file.lastModified() > maxDate )
                    maxDate = file.lastModified();

            if( ! localFolder.setLastModified(maxDate) )
                log.debug( "Unable to set modification date for " + localFolder );
        }
    }

    // TODO: What to do about dupe albums with the same name, possibly containing different pics?
    private boolean downloadImage( ImageSync image, PicasawebClient webClient ) {
        try {
            PhotoEntry photo = image.getRemotePhoto();
            File saveLocation = image.getLocalFile();

            if( webClient.downloadPhoto(saveLocation, photo) )
            {
                // Set the local folder timestamp based on the downloaded file
                updateFolderTimeStamp( localFolder );
            }

            return true;

        } catch (Exception ex) {

            log.error("Exception reading local save location...", ex);
        }

        return false;
    }
}
