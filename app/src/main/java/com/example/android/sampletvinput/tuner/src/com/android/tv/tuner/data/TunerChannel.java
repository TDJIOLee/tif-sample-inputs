/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.data;

import android.database.Cursor;
import android.support.annotation.NonNull;
import android.util.Log;
import com.android.tv.common.util.StringUtils;
import com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.Channel;
import com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.Channel.TunerChannelProto;
import com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.Track.AtscAudioTrack;
import com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.Track.AtscCaptionTrack;
import com.example.android.sampletvinput.tuner.src.com.android.tv.tuner.util.Ints;
import com.google.protobuf.nano.MessageNano;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A class that represents a single channel accessible through a tuner. */
public class TunerChannel implements Comparable<TunerChannel>, PsipData.TvTracksInterface {
    private static final String TAG = "TunerChannel";

    /** Channel number separator between major number and minor number. */
    public static final char CHANNEL_NUMBER_SEPARATOR = '-';

    // See ATSC Code Points Registry.
    private static final String[] ATSC_SERVICE_TYPE_NAMES =
            new String[] {
                "ATSC Reserved",
                "Analog television channels",
                "ATSC_digital_television",
                "ATSC_audio",
                "ATSC_data_only_service",
                "Software Download",
                "Unassociated/Small Screen Service",
                "Parameterized Service",
                "ATSC NRT Service",
                "Extended Parameterized Service"
            };
    private static final String ATSC_SERVICE_TYPE_NAME_RESERVED =
            ATSC_SERVICE_TYPE_NAMES[Channel.AtscServiceType.SERVICE_TYPE_ATSC_RESERVED_VALUE];

    public static final int INVALID_FREQUENCY = -1;

    // According to RFC4259, The number of available PIDs ranges from 0 to 8191.
    public static final int INVALID_PID = -1;

    // According to ISO13818-1, Mpeg2 StreamType has a range from 0x00 to 0xff.
    public static final int INVALID_STREAMTYPE = -1;

    // @GuardedBy(this) Writing operations and toByteArray will be guarded. b/34197766
    private final TunerChannelProto.Builder mProto;

    private TunerChannel(
            PsipData.VctItem channel, int programNumber, List<PsiData.PmtItem> pmtItems, Channel.TunerType type) {
        mProto = TunerChannelProto.newBuilder();
        if (channel == null) {
            mProto.setShortName("");
            mProto.setTsid(0);
            mProto.setProgramNumber(programNumber);
            mProto.setVirtualMajor(0);
            mProto.setVirtualMinor(0);
        } else {
            mProto.setShortName(channel.getShortName());
            if (channel.getLongName() != null) {
                mProto.setLongName(channel.getLongName());
            }
            mProto.setTsid(channel.getChannelTsid());
            mProto.setProgramNumber(channel.getProgramNumber());
            mProto.setVirtualMajor(channel.getMajorChannelNumber());
            mProto.setVirtualMinor(channel.getMinorChannelNumber());
            if (channel.getDescription() != null) {
                mProto.setDescription(channel.getDescription());
            }
            mProto.setServiceType(Channel.AtscServiceType.forNumber(channel.getServiceType()));
        }
        initProto(pmtItems, type);
    }

    private void initProto(List<PsiData.PmtItem> pmtItems, Channel.TunerType type) {
        mProto.setType(type);
        mProto.setChannelId(-1L);
        mProto.setFrequency(INVALID_FREQUENCY);
        mProto.setVideoPid(INVALID_PID);
        //mProto.setVideoStreamType(INVALID_STREAMTYPE);
        List<Integer> audioPids = new ArrayList<>();
        List<Channel.AudioStreamType> audioStreamTypes = new ArrayList<>();
        for (PsiData.PmtItem pmt : pmtItems) {
            switch (pmt.getStreamType()) {
                    // MPEG ES stream video types
                case Channel.VideoStreamType.MPEG1_VALUE:
                case Channel.VideoStreamType.MPEG2_VALUE:
                case Channel.VideoStreamType.H263_VALUE:
                case Channel.VideoStreamType.H264_VALUE:
                case Channel.VideoStreamType.H265_VALUE:
                    mProto.setVideoPid(pmt.getEsPid());
                    mProto.setVideoStreamType(Channel.VideoStreamType.forNumber(pmt.getStreamType()));
                    break;

                    // MPEG ES stream audio types
                case Channel.AudioStreamType.MPEG1AUDIO_VALUE:
                case Channel.AudioStreamType.MPEG2AUDIO_VALUE:
                case Channel.AudioStreamType.MPEG2AACAUDIO_VALUE:
                case Channel.AudioStreamType.MPEG4LATMAACAUDIO_VALUE:
                case Channel.AudioStreamType.A52AC3AUDIO_VALUE:
                case Channel.AudioStreamType.EAC3AUDIO_VALUE:
                    audioPids.add(pmt.getEsPid());
                    audioStreamTypes.add(Channel.AudioStreamType.forNumber(pmt.getStreamType()));
                    break;

                    // Non MPEG ES stream types
                case 0x100: // PmtItem.ES_PID_PCR:
                    mProto.setPcrPid(pmt.getEsPid());
                    break;
                default:
                    // fall out
            }
        }
        mProto.addAllAudioPids(audioPids);
        mProto.addAllAudioStreamTypes(audioStreamTypes);
        mProto.setAudioTrackIndex((audioPids.size() > 0) ? 0 : -1);
    }

    private TunerChannel(
            int programNumber, Channel.TunerType type, PsipData.SdtItem channel, List<PsiData.PmtItem> pmtItems) {
        mProto = TunerChannelProto.newBuilder();
        mProto.setTsid(0);
        mProto.setVirtualMajor(0);
        mProto.setVirtualMinor(0);
        if (channel == null) {
            mProto.setShortName("");
            mProto.setProgramNumber(programNumber);
        } else {
            mProto.setShortName(channel.getServiceName());
            mProto.setProgramNumber(channel.getServiceId());
            mProto.setServiceType(Channel.AtscServiceType.forNumber(channel.getServiceType()));
        }
        initProto(pmtItems, type);
    }

    /** Initialize tuner channel with VCT items and PMT items. */
    public TunerChannel(PsipData.VctItem channel, List<PsiData.PmtItem> pmtItems) {
        this(channel, 0, pmtItems, Channel.TunerType.TYPE_TUNER);
    }

    /** Initialize tuner channel with program number and PMT items. */
    public TunerChannel(int programNumber, List<PsiData.PmtItem> pmtItems) {
        this(null, programNumber, pmtItems, Channel.TunerType.TYPE_TUNER);
    }

    /** Initialize tuner channel with SDT items and PMT items. */
    public TunerChannel(PsipData.SdtItem channel, List<PsiData.PmtItem> pmtItems) {
        this(0, Channel.TunerType.TYPE_TUNER, channel, pmtItems);
    }

    private TunerChannel(TunerChannelProto.Builder tunerChannelProto) {
        mProto = tunerChannelProto;
    }

    public static TunerChannel forFile(PsipData.VctItem channel, List<PsiData.PmtItem> pmtItems) {
        return new TunerChannel(channel, 0, pmtItems, Channel.TunerType.TYPE_FILE);
    }

    public static TunerChannel forDvbFile(
            PsipData.SdtItem channel, List<PsiData.PmtItem> pmtItems) {
        return new TunerChannel(0, Channel.TunerType.TYPE_FILE, channel, pmtItems);
    }

    /**
     * Create a TunerChannel object suitable for network tuners
     *
     * @param major Channel number major
     * @param minor Channel number minor
     * @param programNumber Program number
     * @param shortName Short name
     * @param recordingProhibited Recording prohibition info
     * @param videoFormat Video format. Should be {@code null} or one of the followings: {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_240P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_360P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_480I}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_480P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_576I}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_576P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_720P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_1080I}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_1080P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_2160P}, {@link
     *     android.media.tv.TvContract.Channels#VIDEO_FORMAT_4320P}
     * @return a TunerChannel object
     */
    public static TunerChannel forNetwork(
            int major,
            int minor,
            int programNumber,
            String shortName,
            boolean recordingProhibited,
            String videoFormat) {
        TunerChannel tunerChannel =
                new TunerChannel(
                        null,
                        programNumber,
                        Collections.EMPTY_LIST,
                        Channel.TunerType.TYPE_NETWORK);
        tunerChannel.setVirtualMajor(major);
        tunerChannel.setVirtualMinor(minor);
        tunerChannel.setShortName(shortName);
        // Set audio and video pids in order to work around the audio-only channel check.
        tunerChannel.setAudioPids(new ArrayList<>(Arrays.asList(0)));
        tunerChannel.selectAudioTrack(0);
        tunerChannel.setVideoPid(0);
        tunerChannel.setRecordingProhibited(recordingProhibited);
        if (videoFormat != null) {
            tunerChannel.setVideoFormat(videoFormat);
        }
        return tunerChannel;
    }

    public String getName() {
        return (!mProto.getShortName().isEmpty()) ? mProto.getShortName() : mProto.getLongName();
    }

    public String getShortName() {
        return mProto.getShortName();
    }

    public int getProgramNumber() {
        return mProto.getProgramNumber();
    }

    public Channel.AtscServiceType getServiceType() {
        return mProto.getServiceType();
    }

    public String getServiceTypeName() {
        Channel.AtscServiceType serviceType = mProto.getServiceType();
        if (serviceType.getNumber() >= 0 && serviceType.getNumber() < ATSC_SERVICE_TYPE_NAMES.length) {
            return ATSC_SERVICE_TYPE_NAMES[serviceType.getNumber()];
        }
        return ATSC_SERVICE_TYPE_NAME_RESERVED;
    }

    public int getVirtualMajor() {
        return mProto.getVirtualMajor();
    }

    public int getVirtualMinor() {
        return mProto.getVirtualMinor();
    }

    public int getFrequency() {
        return mProto.getFrequency();
    }

    public String getModulation() {
        return mProto.getModulation();
    }

    public int getTsid() {
        return mProto.getTsid();
    }

    public int getVideoPid() {
        return mProto.getVideoPid();
    }

    public synchronized void setVideoPid(int videoPid) {
        mProto.setVideoPid(videoPid);
    }

    public Channel.VideoStreamType getVideoStreamType() {
        return mProto.getVideoStreamType();
    }

    public int getAudioPid() {
        if (mProto.getAudioTrackIndex() == -1) {
            return INVALID_PID;
        }
        return mProto.getAudioPids(mProto.getAudioTrackIndex());
    }

    public int getAudioStreamType() {
        if (mProto.getAudioTrackIndex() == -1) {
            return INVALID_STREAMTYPE;
        }
        return mProto.getAudioStreamTypes(mProto.getAudioTrackIndex()).getNumber();
    }

    public List<Integer> getAudioPids() {
        return mProto.getAudioPidsList();
    }

    public synchronized void setAudioPids(List<Integer> audioPids) {
        mProto.addAllAudioPids(audioPids);
    }

    public List<Channel.AudioStreamType> getAudioStreamTypes() {
        return mProto.getAudioStreamTypesList();
    }

    public synchronized void setAudioStreamTypes(List<Channel.AudioStreamType> audioStreamTypes) {
        mProto.addAllAudioStreamTypes(audioStreamTypes);
    }

    public int getPcrPid() {
        return mProto.getPcrPid();
    }

    public Channel.TunerType getType() {
        return mProto.getType();
    }

    public synchronized void setFilepath(String filepath) {
        mProto.setFilepath(filepath == null ? "" : filepath);
    }

    public String getFilepath() {
        return mProto.getFilepath();
    }

    public synchronized void setVirtualMajor(int virtualMajor) {
        mProto.setVirtualMajor(virtualMajor);
    }

    public synchronized void setVirtualMinor(int virtualMinor) {
        mProto.setVirtualMinor(virtualMinor);
    }

    public synchronized void setShortName(String shortName) {
        mProto.setShortName(shortName == null ? "" : shortName);
    }

    public synchronized void setFrequency(int frequency) {
        mProto.setFrequency(frequency);
    }

    public synchronized void setModulation(String modulation) {
        mProto.setModulation(modulation == null ? "" : modulation);
    }

    public boolean hasVideo() {
        return mProto.getVideoPid() != INVALID_PID;
    }

    public boolean hasAudio() {
        return getAudioPid() != INVALID_PID;
    }

    public long getChannelId() {
        return mProto.getChannelId();
    }

    public synchronized void setChannelId(long channelId) {
        mProto.setChannelId(channelId);
    }

    /**
     * The flag indicating whether this TV channel is locked or not. This is primarily used for
     * alternative parental control to prevent unauthorized users from watching the current channel
     * regardless of the content rating
     *
     * @see <a
     *     href="https://developer.android.com/reference/android/media/tv/TvContract.Channels.html#COLUMN_LOCKED">link</a>
     */
    public boolean isLocked() {
        return mProto.getLocked();
    }

    public synchronized void setLocked(boolean locked) {
        mProto.setLocked(locked);
    }

    public String getDisplayNumber() {
        return getDisplayNumber(true);
    }

    public String getDisplayNumber(boolean ignoreZeroMinorNumber) {
        if (mProto.getVirtualMajor() != 0 && (mProto.getVirtualMinor() != 0 || !ignoreZeroMinorNumber)) {
            return String.format(
                    "%d%c%d", mProto.getVirtualMajor(), CHANNEL_NUMBER_SEPARATOR, mProto.getVirtualMinor());
        } else if (mProto.getVirtualMajor() != 0) {
            return Integer.toString(mProto.getVirtualMajor());
        } else {
            return Integer.toString(mProto.getProgramNumber());
        }
    }

    public String getDescription() {
        return mProto.getDescription();
    }

    @Override
    public synchronized void setHasCaptionTrack() {
        mProto.setHasCaptionTrack(true);
    }

    @Override
    public boolean hasCaptionTrack() {
        return mProto.getHasCaptionTrack();
    }

    @Override
    public List<AtscAudioTrack> getAudioTracks() {
        return Collections.unmodifiableList(mProto.getAudioTracksList());
    }

    public synchronized void setAudioTracks(List<AtscAudioTrack> audioTracks) {
        mProto.addAllAudioTracks(audioTracks);
    }

    @Override
    public List<AtscCaptionTrack> getCaptionTracks() {
        return Collections.unmodifiableList(mProto.getCaptionTracksList());
    }

    public synchronized void setCaptionTracks(List<AtscCaptionTrack> captionTracks) {
        mProto.addAllCaptionTracks(captionTracks);
    }

    public synchronized void selectAudioTrack(int index) {
        if (0 <= index && index < mProto.getAudioPidsList().size()) {
            mProto.setAudioTrackIndex(index);
        } else {
            mProto.setAudioTrackIndex(-1);
        }
    }

    public synchronized void setRecordingProhibited(boolean recordingProhibited) {
        mProto.setRecordingProhibited(recordingProhibited);
    }

    public boolean isRecordingProhibited() {
        return mProto.getRecordingProhibited();
    }

    public synchronized void setVideoFormat(String videoFormat) {
        mProto.setVideoFormat(videoFormat == null ? "" : videoFormat);
    }

    public String getVideoFormat() {
        return mProto.getVideoFormat();
    }

    @Override
    public String toString() {
        switch (mProto.getType()) {
            case TYPE_FILE: //Channel.TunerType.TYPE_FILE:
                return String.format(
                        "{%d-%d %s} Filepath: %s, ProgramNumber %d",
                        mProto.getVirtualMajor(),
                        mProto.getVirtualMinor(),
                        mProto.getShortName(),
                        mProto.getFilepath(),
                        mProto.getProgramNumber());
                // case Channel.TunerType.TYPE_TUNER:
            default:
                return String.format(
                        "{%d-%d %s} Frequency: %d, ProgramNumber %d",
                        mProto.getVirtualMajor(),
                        mProto.getVirtualMinor(),
                        mProto.getShortName(),
                        mProto.getFrequency(),
                        mProto.getProgramNumber());
        }
    }

    @Override
    public int compareTo(@NonNull TunerChannel channel) {
        // In the same frequency, the program number acts as the sub-channel number.
        int ret = getFrequency() - channel.getFrequency();
        if (ret != 0) {
            return ret;
        }
        ret = getProgramNumber() - channel.getProgramNumber();
        if (ret != 0) {
            return ret;
        }
        ret = StringUtils.compare(getName(), channel.getName());
        if (ret != 0) {
            return ret;
        }
        // For FileTsStreamer, file paths should be compared.
        return StringUtils.compare(getFilepath(), channel.getFilepath());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TunerChannel)) {
            return false;
        }
        return compareTo((TunerChannel) o) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFrequency(), getProgramNumber(), getName(), getFilepath());
    }

    // Serialization
    public synchronized byte[] toByteArray() {
        try {
            return mProto.build().toByteArray(); //MessageNano.toByteArray(mProto);
        } catch (Exception e) {
            // Retry toByteArray. b/34197766
            Log.w(
                    TAG,
                    "TunerChannel or its variables are modified in multiple thread without lock",
                    e);
            return mProto.build().toByteArray(); //MessageNano.toByteArray(mProto)
        }
    }

    public static TunerChannel parseFrom(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return new TunerChannel(TunerChannelProto.parseFrom(data).toBuilder());
        } catch (IOException e) {
            Log.e(TAG, "Could not parse from byte array", e);
            return null;
        }
    }

    public static TunerChannel fromCursor(Cursor cursor) {
        long channelId = cursor.getLong(0);
        boolean locked = cursor.getInt(1) > 0;
        byte[] data = cursor.getBlob(2);
        TunerChannel channel = TunerChannel.parseFrom(data);
        if (channel != null) {
            channel.setChannelId(channelId);
            channel.setLocked(locked);
        }
        return channel;
    }
}
