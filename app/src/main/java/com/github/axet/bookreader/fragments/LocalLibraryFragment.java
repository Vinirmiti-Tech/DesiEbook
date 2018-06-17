package com.github.axet.bookreader.fragments;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.axet.androidlibrary.crypto.MD5;
import com.github.axet.androidlibrary.widgets.AboutPreferenceCompat;
import com.github.axet.androidlibrary.widgets.CacheImagesAdapter;
import com.github.axet.androidlibrary.widgets.SearchView;
import com.github.axet.bookreader.R;
import com.github.axet.bookreader.activities.MainActivity;
import com.github.axet.bookreader.app.BooksCatalogs;
import com.github.axet.bookreader.app.LocalBooksCatalog;
import com.github.axet.bookreader.app.Storage;
import com.github.axet.bookreader.widgets.BrowserDialogFragment;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.geometerplus.android.util.UIUtil;
import org.geometerplus.fbreader.formats.BookReadingException;
import org.geometerplus.fbreader.formats.FormatPlugin;
import org.geometerplus.fbreader.formats.PluginCollection;
import org.geometerplus.fbreader.network.tree.NetworkItemsLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class LocalLibraryFragment extends Fragment implements MainActivity.SearchListener {
    public static final String TAG = LocalLibraryFragment.class.getSimpleName();

    LibraryFragment.FragmentHolder holder;
    LocalLibraryAdapter books;
    Storage storage;
    LocalBooksCatalog n;
    String host;
    BooksCatalogs catalogs;
    Handler handler = new Handler();
    Runnable invalidateOptionsMenu = new Runnable() {
        @Override
        public void run() {
            ActivityCompat.invalidateOptionsMenu(getActivity());
        }
    };

    public static class ByCreated implements Comparator<Item> {

        @Override
        public int compare(Item o1, Item o2) {
            if (o1 instanceof Folder && o2 instanceof Folder) {
                return Integer.valueOf(((Folder) o1).order).compareTo(((Folder) o2).order);
            }
            if (o1 instanceof Folder && o2 instanceof Book) {
                return Integer.valueOf(((Folder) o1).order).compareTo(((Book) o2).folder.order);
            }
            if (o1 instanceof Book && o2 instanceof Folder) {
                return Integer.valueOf(((Book) o1).folder.order).compareTo(((Folder) o2).order);
            }
            Book b1 = (Book) o1;
            Book b2 = (Book) o2;
            int r = Integer.valueOf(b1.folder.order).compareTo(b2.folder.order);
            if (r != 0)
                return r;
            return b1.url.getLastPathSegment().compareTo(b2.url.getLastPathSegment());
        }

    }

    public interface Item {
    }

    public static class Folder implements Item {
        public int order;
        public String name;

        public Folder(String f) {
            name = f;
        }
    }

    public static class Book extends Storage.Book implements Item {
        public Folder folder;

        public Book(Folder ff, File f) {
            url = Uri.fromFile(f);
            folder = ff;
        }

        @TargetApi(21)
        public Book(Folder ff, Uri u) {
            url = u;
            folder = ff;
        }
    }

    public class LocalLibraryAdapter extends LibraryFragment.BooksAdapter {
        Map<String, Folder> folders = new TreeMap<>();
        List<Item> all = new ArrayList<>(); // all items
        List<Item> list = new ArrayList<>(); // filtered list
        String filter;

        public class BookHolder extends LibraryFragment.BooksAdapter.BookHolder {
            TextView folder;

            public BookHolder(View itemView) {
                super(itemView);
                folder = (TextView) itemView.findViewById(R.id.book_folder);
            }
        }

        public LocalLibraryAdapter() {
            super(LocalLibraryFragment.this.getContext(), LocalLibraryFragment.this.holder);
        }

        @Override
        public int getItemViewType(int position) {
            if (list.get(position) instanceof Folder)
                return R.layout.book_folder_item;
            return holder.layout;
        }

        public void load() {
            Uri u = Uri.parse(n.url);
            load(u);
            books.filter = null;
        }

        void load(File f) {
            load(f, f);
        }

        Folder getFolder(File root, File f) {
            String p = root.getPath();
            String n = f.getPath();
            if (n.startsWith(p))
                n = n.substring(p.length());
            File m = new File(n);
            return getFolder(m.getParent());
        }

        @TargetApi(21)
        Folder getFolder(Uri root, Uri u) {
            String p = DocumentsContract.getTreeDocumentId(root);
            String f = DocumentsContract.getDocumentId(u);
            if (f.startsWith(p))
                f = f.substring(p.length());
            File n = new File(f);
            return getFolder(n.getParent());
        }

        Folder getFolder(String s) {
            Folder m = folders.get(s);
            if (m != null)
                return m;
            m = new Folder(s);
            m.order = folders.size();
            folders.put(s, m);
            all.add(m);
            return m;
        }

        void load(File root, File f) {
            File[] ff = f.listFiles();
            if (ff != null) {
                for (File k : ff) {
                    if (k.isDirectory()) {
                        load(root, k);
                    } else {
                        Storage.Detector[] dd = Storage.supported();
                        for (Storage.Detector d : dd) {
                            if (k.toString().toLowerCase(Locale.US).endsWith(d.ext)) {
                                books.all.add(new Book(getFolder(root, k), k));
                                break;
                            }
                        }
                    }
                }
            }
        }

        @TargetApi(21)
        void load(Uri u, Uri childrenUri) {
            ContentResolver contentResolver = storage.getContext().getContentResolver();
            Cursor childCursor = contentResolver.query(childrenUri, null, null, null, null);
            if (childCursor != null) {
                try {
                    while (childCursor.moveToNext()) {
                        String id = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                        String t = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                        String type = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
                        long size = childCursor.getLong(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE));
                        if (type != null && type.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                            Uri k = DocumentsContract.buildChildDocumentsUriUsingTree(u, id);
                            load(u, k);
                        } else if (size > 0) {
                            String n = t.toLowerCase(Locale.US);
                            Storage.Detector[] dd = Storage.supported();
                            for (Storage.Detector d : dd) {
                                if (n.endsWith(d.ext)) {
                                    Uri k = DocumentsContract.buildDocumentUriUsingTree(u, id);
                                    books.all.add(new Book(getFolder(u, k), k));
                                    break;
                                }
                            }
                        }
                    }
                } finally {
                    childCursor.close();
                }
            }
        }

        void load(Uri u) {
            folders.clear();
            all.clear();
            list.clear();
            clearTasks();
            String s = u.getScheme();
            if (Build.VERSION.SDK_INT >= 21 && s.startsWith(ContentResolver.SCHEME_CONTENT)) {
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(u, DocumentsContract.getTreeDocumentId(u));
                load(u, childrenUri);
            } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
                load(Storage.getFile(u));
            } else {
                throw new RuntimeException("unknow uri");
            }
            Collections.sort(all, new ByCreated());
        }

        public void refresh() {
            if (filter == null || filter.isEmpty()) {
                list = all;
                clearTasks();
            } else {
                list = new ArrayList<>();
                Set<Folder> ff = new HashSet<>();
                for (Item a : all) {
                    if (a instanceof Book) {
                        Book b = (Book) a;
                        String t = null;
                        if (b.info != null)
                            t = b.info.title;
                        if (t == null || t.isEmpty()) {
                            t = getDisplayName(b.url);
                        }
                        if (SearchView.filter(filter, t)) {
                            if (!ff.contains(b.folder)) {
                                ff.add(b.folder);
                                list.add(b.folder);
                            }
                            list.add(b);
                        }
                    }
                }
            }
            Collections.sort(list, new ByCreated());
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        public Item getItem(int position) {
            return list.get(position);
        }

        @Override
        public LibraryFragment.BooksAdapter.BookHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            View convertView = inflater.inflate(viewType, parent, false);
            BookHolder h = new BookHolder(convertView);
            return h;
        }

        @Override
        public void onBindViewHolder(final LibraryFragment.BooksAdapter.BookHolder h, int position) {
            View convertView = h.itemView;
            Item i = list.get(position);
            if (i instanceof Book) {
                Book b = (Book) i;
                if (b.info == null || b.cover == null || !b.cover.exists()) {
                    downloadTask(b, convertView);
                } else {
                    downloadTaskClean(convertView);
                    downloadTaskUpdate(null, b, convertView);
                }
                convertView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (holder.clickListener != null)
                            holder.clickListener.onItemClick(null, v, h.getAdapterPosition(), -1);
                    }
                });
                convertView.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (holder.longClickListener != null)
                            holder.longClickListener.onItemLongClick(null, v, h.getAdapterPosition(), -1);
                        return true;
                    }
                });
            }
            if (i instanceof Folder) {
                Folder f = (Folder) i;
                ((BookHolder) h).folder.setText(f.name);
            }
        }

        @Override
        public void downloadTaskUpdate(CacheImagesAdapter.DownloadImageTask task, Object item, Object view) {
            super.downloadTaskUpdate(task, item, view);
            BookHolder h = new BookHolder((View) view);

            Book b = (Book) item;

            if (b.info != null) {
                setText(h.aa, b.info.authors);
                String t = b.info.title;
                if (t == null || t.isEmpty()) {
                    t = getDisplayName(b.url);
                }
                setText(h.tt, t);
            } else {
                setText(h.aa, "");
                setText(h.tt, getDisplayName(b.url));
            }

            if (b.cover != null && b.cover.exists()) {
                ImageView image = (ImageView) ((View) view).findViewById(R.id.book_cover);
                try {
                    Bitmap bm = BitmapFactory.decodeStream(new FileInputStream(b.cover));
                    image.setImageBitmap(bm);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void updateView(CacheImagesAdapter.DownloadImageTask task, ImageView image, ProgressBar progress) {
            super.updateView(task, image, progress);
        }

        @Override
        public Bitmap downloadImageTask(CacheImagesAdapter.DownloadImageTask task) {
            Book book = (Book) task.item;
            try {
                String md5 = MD5.digest(book.url.toString());
                book.md5 = md5;
                book.ext = storage.getExt(book.url).toLowerCase();
                File r = recentFile(book);
                if (r.exists()) {
                    try {
                        book.info = new Storage.RecentInfo(r);
                    } catch (RuntimeException e) {
                        Log.d(TAG, "Unable to load info", e);
                    }
                }
                File cover = coverFile(book);
                if (book.info == null || !cover.exists() || cover.length() == 0) {
                    try {
                        LocalLibraryFragment.this.load(book); // load cover && authors
                    } catch (RuntimeException e) {
                        Log.d(TAG, "unable to load file", e);
                        all.remove(book);
                    }
                } else {
                    book.cover = cover;
                }

                if (book.cover == null)
                    return null;

                return BitmapFactory.decodeStream(new FileInputStream(book.cover));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public File coverFile(Book book) {
        return CacheImagesAdapter.cacheUri(getContext(), book.url);
    }

    public File recentFile(Book book) {
        return new File(n.getCache(), book.md5 + "." + Storage.JSON_EXT);
    }

    public void load(final Book book) {
        if (book.info == null) {
            File r = recentFile(book);
            if (r.exists())
                try {
                    book.info = new Storage.RecentInfo(r);
                } catch (RuntimeException e) {
                    Log.d(TAG, "Unable to load info", e);
                }
        }
        if (book.info == null) {
            book.info = new Storage.RecentInfo();
            book.info.created = System.currentTimeMillis();
        }
        book.info.md5 = book.md5;

        Storage.FBook fbook = null;

        if (book.info.authors == null || book.info.authors.isEmpty()) {
            if (fbook == null)
                fbook = storage.read(book);
            book.info.authors = fbook.book.authorsString(", ");
        }
        if (book.info.title == null || book.info.title.isEmpty() || book.info.title.equals(book.md5)) {
            if (fbook == null)
                fbook = storage.read(book);
            book.info.title = Storage.getTitle(book, fbook);
        }
        if (book.cover == null) {
            File cover = coverFile(book);
            if (!cover.exists() || cover.length() == 0) {
                if (fbook == null)
                    fbook = storage.read(book);
                storage.createCover(fbook, cover);
            }
            book.cover = cover;
        }
        save(book);

        if (fbook != null) {
            fbook.close();
        }
    }

    public void save(Book book) {
        book.info.last = System.currentTimeMillis();
        File f = recentFile(book);
        try {
            String json = book.info.save().toString();
            Writer w = new FileWriter(f);
            IOUtils.write(json, w);
            w.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public LocalLibraryFragment() {
    }

    public static LocalLibraryFragment newInstance(String n) {
        LocalLibraryFragment fragment = new LocalLibraryFragment();
        Bundle args = new Bundle();
        args.putString("url", n);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new Storage(getContext());
        catalogs = new BooksCatalogs(getContext());
        final String u = getArguments().getString("url");
        holder = new LibraryFragment.FragmentHolder(getContext()) {
            @Override
            public String getLayout() {
                return MD5.digest(u);
            }

            @Override
            public int getSpanSize(int position) {
                Item i = books.list.get(position);
                if (i instanceof Folder) {
                    RecyclerView.LayoutManager lm = grid.getLayoutManager();
                    if (lm instanceof GridLayoutManager) {
                        return ((GridLayoutManager) lm).getSpanCount();
                    }
                }
                return super.getSpanSize(position);
            }
        };
        books = new LocalLibraryAdapter();
        n = (LocalBooksCatalog) catalogs.find(u);

        setHasOptionsMenu(true);
    }

    void loadDefault() {
        final MainActivity main = (MainActivity) getActivity();
        books.load();
        handler.post(new Runnable() {
            @Override
            public void run() {
                loadView();
            }
        });
    }

    void loadView() {
        loadBooks();
        loadtoolBar();
    }

    void loadBooks() {
        books.clearTasks();
        books.refresh();
    }

    void loadtoolBar() {
        holder.searchtoolbar.removeAllViews();
        if (holder.searchtoolbar.getChildCount() == 0)
            holder.toolbar.setVisibility(View.GONE);
        else
            holder.toolbar.setVisibility(View.VISIBLE);
    }

    void selectToolbar() {
        String id = getArguments().getString("toolbar");
        if (id == null)
            return;
        for (int i = 0; i < holder.searchtoolbar.getChildCount(); i++) {
            View v = holder.searchtoolbar.getChildAt(i);
            NetworkItemsLoader b = (NetworkItemsLoader) v.getTag();
            ImageButton k = (ImageButton) v.findViewById(R.id.toolbar_icon_image);
            if (b.Tree.getUniqueKey().Id.equals(id)) {
                int[] states = new int[]{
                        android.R.attr.state_checked,
                };
                k.setImageState(states, false);
            } else {
                int[] states = new int[]{
                        -android.R.attr.state_checked,
                };
                k.setImageState(states, false);
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_library, container, false);

        final MainActivity main = (MainActivity) getActivity();

        holder.create(v);
        holder.footer.setVisibility(View.GONE);
        holder.footerNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UIUtil.wait("search", new Runnable() {
                    @Override
                    public void run() {
                    }
                }, getContext());
            }
        });

        main.toolbar.setTitle(R.string.app_name);
        holder.grid.setAdapter(books);
        holder.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                try {
                    final Item i = books.getItem(position);
                    Book b = (Book) i;
                    loadBook(b);
                } catch (RuntimeException e) {
                    main.Error(e);
                }
            }
        });
        return v;
    }

    void loadBook(final Book book) {
        final MainActivity main = (MainActivity) getActivity();
        main.loadBook(book.url, null);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        loadtoolBar();
        selectToolbar();
    }

    @Override
    public void onStart() {
        super.onStart();
        final MainActivity main = (MainActivity) getActivity();
        main.setFullscreen(false);
        main.restoreNetworkSelection(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    public void search(final String ss) {
        if (ss == null || ss.isEmpty())
            return;
        books.filter = ss;
        books.refresh();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        books.clearTasks();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDefault();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public void openBrowser(String u) {
        if (Build.VERSION.SDK_INT < 11) {
            AboutPreferenceCompat.openUrl(getContext(), u);
        } else {
            BrowserDialogFragment b = BrowserDialogFragment.create(u);
            b.show(getFragmentManager(), "");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_home) {
            openBrowser(host);
            return true;
        }
        if (holder.onOptionsItemSelected(item)) {
            invalidateOptionsMenu.run();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        if (Build.VERSION.SDK_INT < 11) {
            invalidateOptionsMenu = new Runnable() {
                @Override
                public void run() {
                    onCreateOptionsMenu(menu, null);
                }
            };
        }

        MenuItem homeMenu = menu.findItem(R.id.action_home);
        MenuItem tocMenu = menu.findItem(R.id.action_toc);
        MenuItem searchMenu = menu.findItem(R.id.action_search);
        MenuItem reflow = menu.findItem(R.id.action_reflow);
        MenuItem fontsize = menu.findItem(R.id.action_fontsize);
        MenuItem debug = menu.findItem(R.id.action_debug);
        MenuItem rtl = menu.findItem(R.id.action_rtl);
        MenuItem mode = menu.findItem(R.id.action_mode);

        reflow.setVisible(false);
        fontsize.setVisible(false);
        debug.setVisible(false);
        rtl.setVisible(false);
        tocMenu.setVisible(false);
        mode.setVisible(false);

        searchMenu.setVisible(true);

        if (host == null || host.isEmpty())
            homeMenu.setVisible(false);
        else
            homeMenu.setVisible(true);
    }

    @Override
    public void searchClose() {
        books.filter = null;
        books.refresh();
    }

    @Override
    public String getHint() {
        return getString(R.string.search_local);
    }

    public String getDisplayName(Uri u) {
        String p = Storage.getDocumentPath(u);
        return ".../" + new File(p).getName();
    }
}
