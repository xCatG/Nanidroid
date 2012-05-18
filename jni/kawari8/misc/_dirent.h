/*
	minimal replacememt on Win32 : open/read/rewind/closedir
	note: errno には何も設定してない。
*/

#ifndef	__STRICT_ANSI__

#ifndef _DIRENT_H_
#define _DIRENT_H_

#include <windows.h>

struct dirent {
	char	d_name[MAX_PATH];
};

typedef struct DIR {
	HANDLE				hdir;
	int					cnt;
	DWORD				lasterr;
	char				spath[MAX_PATH];
	struct dirent		ent;
	WIN32_FIND_DATAA	fd;					/* non-wchar specific */
} DIR;

DIR *opendir(const char *dirname);
struct dirent *readdir(DIR *d);
void closedir(DIR *d);
void rewinddir(DIR *d);

#endif	/* Not _DIRENT_H_ */

#endif	/* Not __STRICT_ANSI__ */
