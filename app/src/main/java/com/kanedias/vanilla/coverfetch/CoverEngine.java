/*
 * Copyright (C) 2017 Oleg Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.coverfetch;

import android.content.Loader;
import android.os.HandlerThread;

/**
 * Interface for various engines for cover extraction
 *
 * @author Oleg Chernovskiy
 */
public interface CoverEngine {

    /**
     * Synchronous call to engine to retrieve cover. Most likely to be used in {@link HandlerThread}
     * or {@link Loader}
     * @param artistName band or artist name to search for
     * @param albumName full album name to search for
     * @return byte array containing album cover if available, null if nothing found
     */
    byte[] getCover(String artistName, String albumName);

    /**
     * Synchronous call to engine to retrieve cover. Most likely to be used in {@link HandlerThread}
     * or {@link Loader}
     * @param query raw query to search for
     * @return byte array containing album cover if available, null if nothing found
     */
    byte[] getCover(String query);
}
