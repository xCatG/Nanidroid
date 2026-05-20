/*
	minimal replacememt on Win32 : open/read/rewind/closedir
	note: errno には何も設定してない。
*/
#include "misc/_dirent.h"

DIR *opendir(const char *dirname)
{
	DIR	*d;
	
	if(!dirname || strlen(dirname) > MAX_PATH - 2) return NULL;
	
	d = new DIR;
	if(!d) return NULL;
	memset(d, 0, sizeof(DIR));
	strcpy(d->spath, dirname);
	strcat(d->spath, "\\*");
	d->hdir = FindFirstFileA(d->spath, &(d->fd));
	
	if(d->hdir == INVALID_HANDLE_VALUE) {
		delete d;
		d = NULL;
	}
	
	return d;
}


struct dirent *readdir(DIR *d)
{
	if(!d) return NULL;
	
	d->cnt++;
	strcpy(d->ent.d_name, d->fd.cFileName);
	if(!FindNextFileA(d->hdir, &(d->fd))) d->fd.cFileName[0] = '\0';
	
	return (d->ent.d_name[0] != '\0') ? &(d->ent) : NULL;
}


void closedir(DIR *d)
{
	if(d) {
		FindClose(d->hdir);
		delete d;
	}
	return;
}


void rewinddir(DIR *d)
{
	if(d) {
		FindClose(d->hdir);
		d->cnt = 0;
		memset(&(d->fd), 0, sizeof(WIN32_FIND_DATAA));
		d->hdir = FindFirstFileA(d->spath, &(d->fd));
	}
	return;
}
